package com.softdevcode.ws_finflow.service;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class RecurringRuleService {
    private final JdbcTemplate jdbc;
    private final CategoryAccessValidator categories;
    private final FinancialAccountService accounts;
    private static final ZoneId ZONE = ZoneId.of("America/Bogota");

    public record Rule(Long id,String title,BigDecimal amount,String type,Long categoryId,
                       Long accountId,int dayOfMonth,LocalDate nextDueDate,boolean active){}
    public record RuleRequest(String title,BigDecimal amount,String type,Long categoryId,
                              Long accountId,int dayOfMonth){}
    public record ActivationRequest(boolean active){}

    @Transactional(readOnly = true)
    public List<Rule> list(Integer userId) {
        return jdbc.query("""
            SELECT id,title,amount,type,category_id,account_id,day_of_month,next_due_date,active
            FROM recurring_rules WHERE user_id=? ORDER BY active DESC,next_due_date ASC,id ASC
            """,(rs,i)->new Rule(rs.getLong("id"),rs.getString("title"),rs.getBigDecimal("amount"),
                rs.getString("type"),rs.getLong("category_id"),
                (Long)rs.getObject("account_id"),rs.getInt("day_of_month"),
                rs.getDate("next_due_date").toLocalDate(),rs.getBoolean("active")),userId);
    }

    @Transactional
    public Rule create(Integer userId, RuleRequest input) {
        if(input==null||input.title()==null||input.type()==null||input.categoryId()==null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Completa los datos de recurrencia");
        String title=input.title().trim().replaceAll("\\s+"," ");
        if(title.length()<2||title.length()>120)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Título de 2 a 120 caracteres");
        if(!"income".equals(input.type())&&!"expense".equals(input.type()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Tipo inválido");
        if(input.dayOfMonth()<1||input.dayOfMonth()>28)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Selecciona un día entre 1 y 28");
        BigDecimal amount=validAmount(input.amount());
        categories.requireVisible(userId,input.categoryId(),input.type());
        accounts.assertOwnedAndCurrency(userId,input.accountId(),"COP");
        LocalDate today=LocalDate.now(ZONE);
        LocalDate due=today.withDayOfMonth(input.dayOfMonth());
        if(due.isBefore(today)) due=due.plusMonths(1);
        Long id=jdbc.queryForObject("""
            INSERT INTO recurring_rules(user_id,title,amount,type,category_id,account_id,
                  day_of_month,next_due_date,currency)
            VALUES(?,?,?,?,?,?,?,?,'COP') RETURNING id
            """,Long.class,userId,title,amount,input.type(),input.categoryId(),
                    input.accountId(),input.dayOfMonth(),Date.valueOf(due));
        return list(userId).stream().filter(r->r.id().equals(id)).findFirst().orElseThrow();
    }

    @Transactional
    public Rule activate(Integer userId,Long id,ActivationRequest req) {
        if(req==null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        int affected=jdbc.update("""
           UPDATE recurring_rules SET active=? WHERE id=? AND user_id=?
           """,req.active(),id,userId);
        if(affected==0) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Regla no encontrada");
        return list(userId).stream().filter(r->r.id().equals(id)).findFirst().orElseThrow();
    }

    @Transactional
    public void delete(Integer userId,Long id) {
        // Keep past financial entries, but unlink their execution journal via FK ON DELETE CASCADE.
        int affected=jdbc.update("DELETE FROM recurring_rules WHERE id=? AND user_id=?",id,userId);
        if(affected==0) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Regla no encontrada");
    }

    // Transactional row-lock + unique (rule_id, occurrence_date) guarantees idempotency
    // across concurrent scheduler instances or manual executions.
    @Transactional
    public int processDue(Integer userId) {
        LocalDate today=LocalDate.now(ZONE);
        List<Rule> due=jdbc.query("""
            SELECT id,title,amount,type,category_id,account_id,day_of_month,next_due_date,active
            FROM recurring_rules WHERE user_id=? AND active AND next_due_date<=?
            ORDER BY next_due_date ASC,id ASC FOR UPDATE SKIP LOCKED
            """,(rs,i)->new Rule(rs.getLong("id"),rs.getString("title"),rs.getBigDecimal("amount"),
                rs.getString("type"),rs.getLong("category_id"),
                (Long)rs.getObject("account_id"),rs.getInt("day_of_month"),
                rs.getDate("next_due_date").toLocalDate(),rs.getBoolean("active")),
            userId,Date.valueOf(today));
        int created=0;
        for(Rule r:due) {
            LocalDate next=r.nextDueDate();
            // Hard cap guards against pathological backlog; remaining dates process at next run.
            for(int i=0;i<24&&!next.isAfter(today);i++) {
                if(r.accountId()!=null) {
                    List<Map<String,Object>> active=jdbc.queryForList("""
                        SELECT id FROM financial_accounts WHERE user_id=? AND id=? AND NOT archived
                        """,userId,r.accountId());
                    if(active.isEmpty()) {
                        jdbc.update("UPDATE recurring_rules SET active=false WHERE id=? AND user_id=?",r.id(),userId);
                        break;
                    }
                }
                List<Map<String,Object>> inserted=jdbc.queryForList("""
                    INSERT INTO recurring_executions(rule_id,user_id,occurrence_date)
                    VALUES(?,?,?) ON CONFLICT (rule_id,occurrence_date) DO NOTHING RETURNING id
                    """,r.id(),userId,Date.valueOf(next));
                if(!inserted.isEmpty()) {
                    Long transactionId=jdbc.queryForObject("""
                        INSERT INTO transactions(user_id,amount,type,category_id,account_id,
                              description,date,currency,status)
                        VALUES(?,?,?,?,?,? ,?,'COP','completed') RETURNING id
                        """,Long.class,userId,r.amount(),r.type(),r.categoryId(),r.accountId(),
                            r.title(),Timestamp.valueOf(next.atStartOfDay()));
                    jdbc.update("UPDATE recurring_executions SET transaction_id=? WHERE id=? AND user_id=?",
                            transactionId,inserted.get(0).get("id"),userId);
                    created++;
                }
                next=next.plusMonths(1);
                jdbc.update("UPDATE recurring_rules SET next_due_date=? WHERE id=? AND user_id=?",
                            Date.valueOf(next),r.id(),userId);
            }
        }
        return created;
    }

    private BigDecimal validAmount(BigDecimal value) {
        if(value==null||value.signum()<=0)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"El monto debe ser positivo");
        try {
            BigDecimal exact=value.setScale(2,RoundingMode.UNNECESSARY);
            if(exact.precision()>15)
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Monto demasiado grande");
            return exact;
        }catch(ArithmeticException ex){
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Máximo dos decimales");
        }
    }
}
