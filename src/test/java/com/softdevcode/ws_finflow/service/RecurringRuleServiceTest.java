package com.softdevcode.ws_finflow.service;

import com.softdevcode.ws_finflow.repository.CategoryRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RecurringRuleServiceTest {
 @Mock JdbcTemplate jdbc;
 @Mock CategoryAccessValidator categories;
 @Mock FinancialAccountService accounts;
 @InjectMocks RecurringRuleService service;

 @Test void rejectsInvalidDaysBeforeWriting() {
   var rule=new RecurringRuleService.RuleRequest("Salario",new BigDecimal("2500000"),"income",1L,null,31);
   assertEquals(HttpStatus.BAD_REQUEST,assertThrows(ResponseStatusException.class,
       ()->service.create(10,rule)).getStatusCode());
   verifyNoInteractions(jdbc);
 }
 @Test void rejectsNonCOPPrecisionBeforeWriting() {
   var rule=new RecurringRuleService.RuleRequest("Salario",new BigDecimal("2000.999"),"income",1L,null,10);
   assertEquals(HttpStatus.BAD_REQUEST,assertThrows(ResponseStatusException.class,
       ()->service.create(10,rule)).getStatusCode());
   verifyNoInteractions(jdbc);
 }
 @Test void rejectsInvalidType() {
   var rule=new RecurringRuleService.RuleRequest("Salario",new BigDecimal("2000.00"),"withdraw",1L,null,10);
   assertEquals(HttpStatus.BAD_REQUEST,assertThrows(ResponseStatusException.class,
       ()->service.create(10,rule)).getStatusCode());
 }
}
