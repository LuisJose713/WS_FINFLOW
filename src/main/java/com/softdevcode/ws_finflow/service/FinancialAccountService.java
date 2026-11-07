package com.softdevcode.ws_finflow.service;

import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class FinancialAccountService {
    private final JdbcTemplate jdbc;

    public record AccountView(Long id, String name, String type, String currency,
                              BigDecimal openingBalance, BigDecimal balance, boolean archived) {}
    public record AccountRequest(String name, String type, String currency, BigDecimal openingBalance) {}
    public record AccountUpdate(String name, String type) {}
    public record ArchiveRequest(boolean archived) {}
    public record TransferRequest(Long fromAccountId, Long toAccountId, BigDecimal amount,
                                  LocalDate date, String note) {}
    public record TransferView(Long id, Long fromAccountId, Long toAccountId,
                               BigDecimal amount, LocalDate date, String note) {}

    private static final Set<String> TYPES = Set.of("cash", "bank", "savings", "other");

    @Transactional(readOnly = true)
    public List<AccountView> list(Integer userId) {
        return jdbc.query("""
            SELECT a.id,a.name,a.account_type,a.currency,a.opening_balance,a.archived,
                   ROUND(a.opening_balance
                    + COALESCE((SELECT SUM(CASE WHEN t.type='income' THEN t.amount::numeric
                                                  ELSE -t.amount::numeric END)
                       FROM transactions t WHERE t.user_id=a.user_id AND t.account_id=a.id
                       AND t.type IN ('income','expense')
                       AND (t.status='completed' OR t.status IS NULL)
                       AND TRIM(COALESCE(t.currency,'COP'))=a.currency),0)
                    + COALESCE((SELECT SUM(CASE WHEN tr.to_account_id=a.id THEN tr.amount ELSE -tr.amount END)
                       FROM account_transfers tr WHERE tr.user_id=a.user_id
                       AND (tr.from_account_id=a.id OR tr.to_account_id=a.id)),0),2) AS balance
            FROM financial_accounts a WHERE a.user_id=?
            ORDER BY a.archived ASC,a.name ASC,a.id ASC
            """, (rs,i) -> new AccountView(rs.getLong("id"),rs.getString("name"),
                rs.getString("account_type"),rs.getString("currency"),
                rs.getBigDecimal("opening_balance"),rs.getBigDecimal("balance"),
                rs.getBoolean("archived")),userId);
    }

    @Transactional
    public AccountView create(Integer userId, AccountRequest request) {
        if(request==null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Datos requeridos");
        String name = name(request.name());
        String type = type(request.type());
        String currency = request.currency()==null?"COP":request.currency().trim().toUpperCase();
        if(!"COP".equals(currency)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Las nuevas billeteras admiten COP por ahora");
        BigDecimal initial = amount(request.openingBalance()==null?BigDecimal.ZERO:request.openingBalance(),true);
        try {
            Long id = jdbc.queryForObject("""
                INSERT INTO financial_accounts(user_id,name,account_type,currency,opening_balance)
                VALUES(?,?,?,?,?) RETURNING id
                """,Long.class,userId,name,type,currency,initial);
            return list(userId).stream().filter(x -> x.id().equals(id)).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR));
        } catch(DataIntegrityViolationException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,"Ya existe esta cuenta o sus datos son inválidos",ex);
        }
    }

    @Transactional
    public AccountView update(Integer userId, Long id, AccountUpdate request) {
        if(request==null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Datos requeridos");
        try {
            int changed=jdbc.update("UPDATE financial_accounts SET name=?,account_type=? WHERE id=? AND user_id=?",
                name(request.name()),type(request.type()),id,userId);
            if(changed==0) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Cuenta no encontrada");
            return list(userId).stream().filter(x -> x.id().equals(id)).findFirst().orElseThrow();
        } catch(DataIntegrityViolationException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,"Nombre de cuenta duplicado",ex);
        }
    }

    @Transactional
    public AccountView archive(Integer userId, Long id, ArchiveRequest request) {
        if(request==null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Datos requeridos");
        int changed=jdbc.update("UPDATE financial_accounts SET archived=? WHERE id=? AND user_id=?",
            request.archived(),id,userId);
        if(changed==0) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Cuenta no encontrada");
        return list(userId).stream().filter(x -> x.id().equals(id)).findFirst().orElseThrow();
    }

    // Always resolve ownership from the server-side JWT; never trust client userId.
    public void assertOwnedAndCurrency(Integer userId, Long accountId, String currency) {
        if(accountId==null) return;
        List<Map<String,Object>> rows=jdbc.queryForList("""
            SELECT currency FROM financial_accounts
            WHERE id=? AND user_id=? AND NOT archived
            """,accountId,userId);
        if(rows.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Billetera no disponible para este usuario");
        String requested = currency==null?"COP":currency.trim().toUpperCase();
        if(!requested.equals(rows.get(0).get("currency")))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"La moneda debe coincidir con la billetera");
    }

    @Transactional
    public TransferView transfer(Integer userId, TransferRequest request) {
        if(request==null||request.fromAccountId()==null||request.toAccountId()==null ||
          request.fromAccountId().equals(request.toAccountId()))
          throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Selecciona dos cuentas distintas");
        BigDecimal value=amount(request.amount(),false);
        // Lock account rows in deterministic order so parallel transfers cannot overspend.
        List<Long> ids=jdbc.query("""
            SELECT id FROM financial_accounts WHERE user_id=? AND NOT archived
            AND id IN (?,?) ORDER BY id FOR UPDATE
            """,(rs,i)->rs.getLong(1),userId,request.fromAccountId(),request.toAccountId());
        if(ids.size()!=2) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Cuenta no disponible");
        List<AccountView> accounts=list(userId);
        AccountView from=accounts.stream().filter(x->x.id().equals(request.fromAccountId())).findFirst().orElseThrow();
        AccountView to=accounts.stream().filter(x->x.id().equals(request.toAccountId())).findFirst().orElseThrow();
        if(!from.currency().equals(to.currency()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Las cuentas deben tener la misma moneda");
        if(from.balance().compareTo(value)<0)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Saldo insuficiente para transferir");
        String note=request.note()==null?null:request.note().trim();
        if(note!=null&&note.length()>255) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Nota demasiado larga");
        LocalDate date=request.date()==null?LocalDate.now():request.date();
        Long id=jdbc.queryForObject("""
            INSERT INTO account_transfers(user_id,from_account_id,to_account_id,amount,transfer_date,note)
            VALUES(?,?,?,?,?,?) RETURNING id
            """,Long.class,userId,request.fromAccountId(),request.toAccountId(),value,date,note);
        return new TransferView(id,request.fromAccountId(),request.toAccountId(),value,date,note);
    }

    @Transactional(readOnly = true)
    public List<TransferView> transfers(Integer userId) {
        return jdbc.query("""
            SELECT id,from_account_id,to_account_id,amount,transfer_date,note
            FROM account_transfers WHERE user_id=? ORDER BY transfer_date DESC,id DESC
            LIMIT 100
            """,(rs,i)->new TransferView(rs.getLong("id"),rs.getLong("from_account_id"),
                rs.getLong("to_account_id"),rs.getBigDecimal("amount"),
                rs.getDate("transfer_date").toLocalDate(),rs.getString("note")),userId);
    }

    private String name(String value) {
        String v=value==null?"":value.trim().replaceAll("\\s+"," ");
        if(v.length()<2||v.length()>80)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"El nombre debe tener entre 2 y 80 caracteres");
        return v;
    }
    private String type(String value) {
        if(value==null||!TYPES.contains(value))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Tipo de billetera inválido");
        return value;
    }
    private BigDecimal amount(BigDecimal amount,boolean zeroAllowed) {
        if(amount==null || (zeroAllowed?amount.signum()<0:amount.signum()<=0))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Importe inválido");
        try {
            BigDecimal v=amount.setScale(2,RoundingMode.UNNECESSARY);
            if(v.precision()>15) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Importe demasiado grande");
            return v;
        } catch(ArithmeticException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Máximo dos decimales");
        }
    }
}
