package com.softdevcode.ws_finflow.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.softdevcode.ws_finflow.dto.*;
import com.softdevcode.ws_finflow.entity.*;
import com.softdevcode.ws_finflow.repository.*;
import com.softdevcode.ws_finflow.service.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Opt in ONLY with a disposable copy of the database, never the production URL. */
@EnabledIfEnvironmentVariable(named="FINFLOW_ENCRYPTION_INTEGRATION",matches="true")
@SpringBootTest(properties={
    "FINFLOW_FIELD_ENCRYPTION_ENABLED=true",
    "FINFLOW_FIELD_ENCRYPTION_KEY_BASE64=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
    "JWT_SECRET=integration-only-signing-secret-never-for-production-20261114",
    "finflow.recurring.initial-delay-ms=3600000"})
@ActiveProfiles("prod")
@AutoConfigureMockMvc(print=MockMvcPrint.NONE)
class StorageEncryptionIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserRepository users;
    @Autowired CategoryRepository categories;
    @Autowired TransactionService transactions;
    @Autowired FinancialAccountService accounts;
    @Autowired MonthlyBudgetService budgets;
    @Autowired GoalService goals;
    @Autowired PlannedExpenseService planned;
    @Autowired RecurringRuleService recurring;
    @Autowired StorageEncryptionMigration migration;
    @Autowired FieldCipher cipher;

    @Test void preservesExistingRowsAndExercisesEncryptedFinancialAndAuthFlows() throws Exception {
        assertEquals("UP",migration.health().getStatus().getCode());
        // Existing owners and categories must still materialize after the backfill.
        assertFalse(users.findAll().isEmpty());assertFalse(categories.findAll().isEmpty());
        String suffix=UUID.randomUUID().toString().substring(0,10);
        String email="encryption-"+suffix+"@example.invalid";
        String password="Temporary-QA-Password-123!";
        String name="Cifrado QA "+suffix;
        var signup=mvc.perform(post("/auth/register").contentType("application/json").content(mapper.writeValueAsString(
            Map.of("name",name,"email",email,"password",password,"confirmPassword",password))))
            .andExpect(status().isOk()).andReturn();
        var registration=mapper.readTree(signup.getResponse().getContentAsByteArray());
        String token=registration.get("token").asText();
        long owner=registration.at("/user/id").asLong();int userId=Math.toIntExact(owner);
        try {
            mvc.perform(post("/auth/login").contentType("application/json").content(mapper.writeValueAsString(
                Map.of("email",email,"password",password)))).andExpect(status().isOk());
            mvc.perform(get("/auth/me").header("Authorization","Bearer "+token)).andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(email));
            assertTrue(jdbc.queryForObject("SELECT email FROM users WHERE id=?",String.class,owner).startsWith(FieldCipher.PREFIX));
            assertTrue(jdbc.queryForObject("SELECT password FROM users WHERE id=?",String.class,owner).startsWith("$2"));
            Category income=new Category();income.setUserId(userId);income.setName("Nómina privada QA");income.setType("Income");
            income=categories.saveAndFlush(income);
            Category expense=new Category();expense.setUserId(userId);expense.setName("Alimentación privada QA");expense.setType("Expense");
            expense=categories.saveAndFlush(expense);
            var wallet=accounts.create(userId,new FinancialAccountService.AccountRequest("Cuenta privada QA","bank","COP",new BigDecimal("1000.00")));
            var other=accounts.create(userId,new FinancialAccountService.AccountRequest("Ahorro privado QA","savings","COP",BigDecimal.ZERO));
            TransactionRequest input=new TransactionRequest();input.setAmount(200.25);input.setType("income");
            input.setCategoryId(income.getId());input.setAccountId(wallet.id());input.setDescription("Ingreso privado José 💰");input.setDate(LocalDate.now());
            var tx=transactions.create(userId,input);
            TransactionFilter filter=new TransactionFilter();filter.setSearch("josé");
            assertEquals(1,transactions.getFiltered(userId,filter).size());
            assertTrue(jdbc.queryForObject("SELECT amount FROM transactions WHERE id=?",String.class,tx.getId()).startsWith(FieldCipher.PREFIX));
            input.setAmount(75.13);input.setType("expense");input.setCategoryId(expense.getId());input.setDescription("Compra privada QA");
            transactions.create(userId,input);
            assertEquals(new BigDecimal("1125.12"),accounts.list(userId).stream().filter(a->a.id().equals(wallet.id())).findFirst().orElseThrow().balance());
            accounts.transfer(userId,new FinancialAccountService.TransferRequest(wallet.id(),other.id(),new BigDecimal("100.10"),LocalDate.now(),"Nota privada QA"));
            assertEquals(new BigDecimal("1025.02"),accounts.list(userId).stream().filter(a->a.id().equals(wallet.id())).findFirst().orElseThrow().balance());
            assertEquals(new BigDecimal("100.10"),accounts.transfers(userId).get(0).amount());
            var today=LocalDate.now();
            budgets.create(userId,new BudgetRequest(expense.getId(),today.getYear(),today.getMonthValue(),new BigDecimal("300.00")));
            assertEquals(new BigDecimal("75.13"),budgets.list(userId,today.getYear(),today.getMonthValue()).get(0).spent());
            GoalRequest goalInput=new GoalRequest();goalInput.setTitle("Meta privada QA");goalInput.setDescription("Detalle privado");
            goalInput.setTargetAmount(1000.0);goalInput.setDeadline(today.plusMonths(3));
            var goal=goals.create(owner,goalInput);ContributionRequest contribution=new ContributionRequest();contribution.setAmount(15.50);contribution.setNote("Aporte privado");
            goals.addContribution(owner,goal.getId(),contribution);
            assertEquals(15.50,goals.getById(owner,goal.getId()).getCurrentAmount());
            var rule=recurring.create(userId,new RecurringRuleService.RuleRequest("Recurrente privado",new BigDecimal("25.10"),"expense",expense.getId(),wallet.id(),1));
            assertEquals(new BigDecimal("25.10"),recurring.list(userId).get(0).amount());
            jdbc.update("UPDATE recurring_rules SET next_due_date=? WHERE id=?",cipher.encrypt("recurring_rules.next_due_date",today.toString()),rule.id());
            assertEquals(1,recurring.processDue(userId));assertEquals(0,recurring.processDue(userId));
            assertEquals(new BigDecimal("999.92"),accounts.list(userId).stream().filter(a->a.id().equals(wallet.id())).findFirst().orElseThrow().balance());
            PlannedExpenseRequest plan=new PlannedExpenseRequest();plan.setTitle("Programado privado");plan.setNotes("Nota privada");
            plan.setAmount(new BigDecimal("19.99"));plan.setCategoryId(expense.getId());plan.setDueDate(today.plusDays(10));
            assertEquals(new BigDecimal("19.99"),planned.create(userId,plan).getAmount());
            for(String extension:List.of("pdf","xlsx","csv"))
                mvc.perform(get("/transactions/export/"+extension).header("Authorization","Bearer "+token)).andExpect(status().isOk());
            // A second migration must validate the same ciphertext without rewriting rows.
            String original=jdbc.queryForObject("SELECT email FROM users WHERE id=?",String.class,owner);
            migration.migrate();assertEquals(original,jdbc.queryForObject("SELECT email FROM users WHERE id=?",String.class,owner));
            // A missing or wrong key must never change storage or pass health.
            var wrong=new FieldCipher(Base64.getEncoder().encodeToString(new byte[32]),true);
            assertThrows(IllegalStateException.class,()->new StorageEncryptionMigration(jdbc.getDataSource(),wrong).migrate());
            assertEquals(original,jdbc.queryForObject("SELECT email FROM users WHERE id=?",String.class,owner));
            assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->
                jdbc.update("UPDATE transactions SET amount='10' WHERE id=?",tx.getId()));
        } finally {
            mvc.perform(delete("/auth/account").header("Authorization","Bearer "+token).contentType("application/json")
                .content(mapper.writeValueAsString(Map.of("password",password)))).andExpect(status().isNoContent());
            assertFalse(users.existsById(owner));
        }
    }
}
