package com.softdevcode.ws_finflow.service;

import com.softdevcode.ws_finflow.dto.BudgetRequest;
import com.softdevcode.ws_finflow.dto.BudgetLimitRequest;
import com.softdevcode.ws_finflow.entity.MonthlyBudget;
import com.softdevcode.ws_finflow.repository.CategoryRepository;
import com.softdevcode.ws_finflow.repository.MonthlyBudgetRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import java.math.BigDecimal;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MonthlyBudgetServiceTest {
    @Mock MonthlyBudgetRepository budgets;
    @Mock CategoryRepository categories;
    @Mock CategoryAccessValidator validator;
    @Mock JdbcTemplate jdbc;
    @InjectMocks MonthlyBudgetService service;

    @Test void budgetIsCreatedForAuthenticatedOwner() {
        when(budgets.saveAndFlush(any(MonthlyBudget.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        MonthlyBudget saved = service.create(7, new BudgetRequest(9L, 2026, 11, new BigDecimal("250000.25")));
        assertEquals(7, saved.getUserId());
        assertEquals(9L, saved.getCategoryId());
        assertEquals(new BigDecimal("250000.25"), saved.getLimitAmount());
        verify(validator).requireVisible(7, 9L, "expense");
    }

    @Test void rejectsDuplicateBudgetForSameUserAndMonth() {
        when(budgets.existsByUserIdAndCategoryIdAndYearAndMonth(7, 9L, 2026, 11)).thenReturn(true);
        var error = assertThrows(ResponseStatusException.class,
            () -> service.create(7, new BudgetRequest(9L, 2026, 11, new BigDecimal("100"))));
        assertEquals(HttpStatus.CONFLICT, error.getStatusCode());
        verify(budgets, never()).saveAndFlush(any());
    }

    @Test void cannotChangeAnotherUsersBudget() {
        when(budgets.findByIdAndUserId(15L, 7)).thenReturn(Optional.empty());
        var error = assertThrows(ResponseStatusException.class,
            () -> service.changeLimit(7, 15L, new BudgetLimitRequest(new BigDecimal("1000"))));
        assertEquals(HttpStatus.NOT_FOUND, error.getStatusCode());
        verify(budgets, never()).saveAndFlush(any());
    }

    @Test void rejectsExtraMoneyPrecision() {
        var error = assertThrows(ResponseStatusException.class,
            () -> service.create(7, new BudgetRequest(9L, 2026, 11, new BigDecimal("100.009"))));
        assertEquals(HttpStatus.BAD_REQUEST, error.getStatusCode());
        verify(budgets, never()).saveAndFlush(any());
    }

    @Test void rejectsInvalidPeriod() {
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
            () -> service.list(7, 2026, 13)).getStatusCode());
        verifyNoInteractions(jdbc);
    }
}
