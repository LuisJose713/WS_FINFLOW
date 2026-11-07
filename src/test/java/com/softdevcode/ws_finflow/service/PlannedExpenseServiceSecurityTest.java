package com.softdevcode.ws_finflow.service;

import com.softdevcode.ws_finflow.dto.PlannedExpenseRequest;
import com.softdevcode.ws_finflow.entity.PlannedExpense;
import com.softdevcode.ws_finflow.entity.Transaction;
import com.softdevcode.ws_finflow.repository.PlannedExpenseRepository;
import com.softdevcode.ws_finflow.repository.TransactionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PlannedExpenseServiceSecurityTest {
    @Mock PlannedExpenseRepository expenses;
    @Mock TransactionRepository transactions;
    @InjectMocks PlannedExpenseService service;

    @Test
    void rejectsZeroAndNegativeAmounts() {
        PlannedExpenseRequest request = new PlannedExpenseRequest();
        request.setTitle("Nuevo gasto");
        request.setAmount(BigDecimal.ZERO);
        assertEquals(HttpStatus.BAD_REQUEST,
                assertThrows(ResponseStatusException.class, () -> service.create(7, request)).getStatusCode());
        verify(expenses, never()).save(any());
    }

    @Test
    void refusesSecondExecutionOfCompletedExpense() {
        PlannedExpense expense = new PlannedExpense();
        expense.setId(12L);
        expense.setUserId(7);
        expense.setStatus("done");
        when(expenses.lockByIdAndUserId(12L, 7)).thenReturn(Optional.of(expense));
        assertEquals(HttpStatus.BAD_REQUEST,
                assertThrows(ResponseStatusException.class, () -> service.execute(7, 12L)).getStatusCode());
        verifyNoInteractions(transactions);
    }

    @Test
    void ignoresExpenseBelongingToAnotherUser() {
        when(expenses.lockByIdAndUserId(12L, 7)).thenReturn(Optional.empty());
        assertEquals(HttpStatus.NOT_FOUND,
                assertThrows(ResponseStatusException.class, () -> service.execute(7, 12L)).getStatusCode());
        verifyNoInteractions(transactions);
    }

    @Test
    void executesPendingExpenseExactlyOnce() {
        PlannedExpense expense = new PlannedExpense();
        expense.setId(12L);
        expense.setUserId(7);
        expense.setStatus("pending");
        expense.setAmount(new BigDecimal("25000.00"));
        expense.setTitle("Mercado");
        when(expenses.lockByIdAndUserId(12L, 7)).thenReturn(Optional.of(expense));
        Transaction saved = new Transaction();
        saved.setId(55L);
        when(transactions.save(any(Transaction.class))).thenReturn(saved);
        when(expenses.save(expense)).thenReturn(expense);
        PlannedExpense result = service.execute(7, 12L);
        assertEquals("done", result.getStatus());
        assertEquals(55L, result.getExecutedTransactionId());
        verify(transactions, times(1)).save(any(Transaction.class));
    }
}
