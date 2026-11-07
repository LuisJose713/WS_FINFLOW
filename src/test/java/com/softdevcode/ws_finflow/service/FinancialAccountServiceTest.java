package com.softdevcode.ws_finflow.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import java.math.BigDecimal;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FinancialAccountServiceTest {
    @Mock JdbcTemplate jdbc;
    @InjectMocks FinancialAccountService service;

    @Test void rejectsForeignAccountForTransaction() {
        when(jdbc.queryForList(anyString(),eq(4L),eq(7))).thenReturn(List.of());
        assertEquals(HttpStatus.BAD_REQUEST,assertThrows(ResponseStatusException.class,
                ()->service.assertOwnedAndCurrency(7,4L,"COP")).getStatusCode());
    }
    @Test void rejectsSelfTransfer() {
        var request=new FinancialAccountService.TransferRequest(2L,2L,new BigDecimal("10"),null,null);
        assertEquals(HttpStatus.BAD_REQUEST,assertThrows(ResponseStatusException.class,
                ()->service.transfer(7,request)).getStatusCode());
        verifyNoInteractions(jdbc);
    }
    @Test void rejectsNegativeOpeningBalance() {
        var request=new FinancialAccountService.AccountRequest("Caja","cash","COP",new BigDecimal("-50"));
        assertEquals(HttpStatus.BAD_REQUEST,assertThrows(ResponseStatusException.class,
                ()->service.create(7,request)).getStatusCode());
        verifyNoInteractions(jdbc);
    }
    @Test void rejectsPrecisionBeyondCents() {
        var request=new FinancialAccountService.AccountRequest("Caja","cash","COP",new BigDecimal("12.345"));
        assertEquals(HttpStatus.BAD_REQUEST,assertThrows(ResponseStatusException.class,
                ()->service.create(7,request)).getStatusCode());
        verifyNoInteractions(jdbc);
    }
}
