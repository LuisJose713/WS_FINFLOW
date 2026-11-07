package com.softdevcode.ws_finflow.service;

import com.softdevcode.ws_finflow.dto.BudgetOverview;
import com.softdevcode.ws_finflow.entity.Category;
import com.softdevcode.ws_finflow.entity.MonthlyBudget;
import com.softdevcode.ws_finflow.repository.CategoryRepository;
import com.softdevcode.ws_finflow.repository.MonthlyBudgetRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MonthlyBudgetOverviewTest {
    @Mock MonthlyBudgetRepository budgets;
    @Mock CategoryRepository categories;
    @Mock CategoryAccessValidator validator;
    @Mock JdbcTemplate jdbc;
    @InjectMocks MonthlyBudgetService service;

    private void setupRollup(String amount) {
        MonthlyBudget budget = new MonthlyBudget();
        budget.setId(42L);
        budget.setUserId(7);
        budget.setCategoryId(9L);
        budget.setYear(2026);
        budget.setMonth(11);
        budget.setLimitAmount(new BigDecimal("100000.00"));
        Category cat = new Category();
        cat.setId(9L);
        cat.setName("Alimentación");
        cat.setType("Expense");
        when(budgets.findByUserIdAndYearAndMonthOrderByIdAsc(7, 2026, 11)).thenReturn(List.of(budget));
        when(categories.findVisibleTo(7)).thenReturn(List.of(cat));
        when(jdbc.queryForList(anyString(), eq(7), any(Timestamp.class), any(Timestamp.class)))
            .thenReturn(List.of(Map.of("category_id", 9L, "spent", new BigDecimal(amount))));
    }

    @Test void lowerThanEightyPercentDoesNotWarnWhenRounded() {
        setupRollup("79999.99");
        BudgetOverview overview = service.list(7, 2026, 11).get(0);
        assertEquals("ok", overview.status());
        assertEquals(79, overview.percentUsed());
        assertEquals(new BigDecimal("20000.01"), overview.remaining());
        verify(jdbc).queryForList(contains("WHERE user_id = ?"), eq(7),
            eq(Timestamp.valueOf("2026-11-01 00:00:00")),
            eq(Timestamp.valueOf("2026-12-01 00:00:00")));
    }

    @Test void eightyPercentWarnsExactly() {
        setupRollup("80000.00");
        assertEquals("near", service.list(7, 2026, 11).get(0).status());
    }

    @Test void exceedingBudgetIsMarkedOver() {
        setupRollup("120000.25");
        BudgetOverview overview = service.list(7, 2026, 11).get(0);
        assertEquals("over", overview.status());
        assertEquals(new BigDecimal("-20000.25"), overview.remaining());
    }

    @Test void noRowsAvoidsCrossUserRollupQueries() {
        when(budgets.findByUserIdAndYearAndMonthOrderByIdAsc(7, 2026, 11)).thenReturn(List.of());
        assertTrue(service.list(7, 2026, 11).isEmpty());
        verifyNoInteractions(jdbc, categories);
    }
}
