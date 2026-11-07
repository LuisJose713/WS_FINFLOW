package com.softdevcode.ws_finflow.service;

import com.softdevcode.ws_finflow.dto.TransactionStats;
import com.softdevcode.ws_finflow.entity.Category;
import com.softdevcode.ws_finflow.entity.Transaction;
import com.softdevcode.ws_finflow.repository.CategoryRepository;
import com.softdevcode.ws_finflow.repository.TransactionRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TransactionDashboardStatsTest {
    @Mock TransactionRepository transactions;
    @Mock CategoryRepository categories;
    @Mock EntityManager entityManager;
    @Mock CategoryAccessValidator accessValidator;
    @Mock FinancialAccountService accounts;
    @InjectMocks TransactionService service;

    private Transaction transaction(String type, double amount, String status, String currency,
                                    Long categoryId, int month) {
        Transaction tx = new Transaction();
        tx.setUserId(7);
        tx.setType(type);
        tx.setAmount(amount);
        tx.setStatus(status);
        tx.setCurrency(currency);
        tx.setCategoryId(categoryId);
        tx.setDate(LocalDateTime.of(2026, month, 10, 12, 0));
        return tx;
    }

    private Category category(Long id, String name) {
        Category result = new Category();
        result.setId(id);
        result.setName(name);
        result.setType("Expense");
        return result;
    }

    @Test
    void returnsRealCategoryPercentagesAndIgnoresPendingOrForeignCurrency() {
        when(transactions.findByUserIdOrderByDateDesc(7)).thenReturn(List.of(
                transaction("income", 1_000_000, "completed", "COP", null, 11),
                transaction("expense", 200_000, "completed", "COP", 4L, 11),
                transaction("expense", 100_000, "completed", "COP", 5L, 11),
                transaction("expense", 100_000, "pending", "COP", 4L, 11),
                transaction("expense", 50_000, "completed", "USD", 4L, 11),
                transaction("income", 500_000, "cancelled", "COP", null, 11)
        ));
        when(categories.findVisibleTo(7)).thenReturn(List.of(
                category(4L, "Alimentación"),
                category(5L, "Transporte")
        ));

        TransactionStats stats = service.getStats(7);
        assertEquals(1_000_000, stats.getTotalIncome(), 0.001);
        assertEquals(300_000, stats.getTotalExpenses(), 0.001);
        assertEquals(700_000, stats.getBalance(), 0.001);
        assertEquals(70, stats.getSavingsRate(), 0.001);
        assertEquals(2, stats.getCategoryData().size());
        assertEquals("Alimentación", stats.getCategoryData().get(0).get("category"));
        assertEquals(200_000, (double) stats.getCategoryData().get(0).get("amount"), 0.001);
        assertEquals(66.6667, (double) stats.getCategoryData().get(0).get("percentage"), 0.01);
        assertEquals("Transporte", stats.getCategoryData().get(1).get("category"));
        assertEquals("2026-11", stats.getMonthlyData().get(0).get("month"));
        verify(transactions).findByUserIdOrderByDateDesc(7);
        verify(categories).findVisibleTo(7);
    }

    @Test
    void emptyIncomeAndExpensesProducesSafeZeroes() {
        when(transactions.findByUserIdOrderByDateDesc(7)).thenReturn(List.of());
        when(categories.findVisibleTo(7)).thenReturn(List.of());

        TransactionStats stats = service.getStats(7);
        assertEquals(0, stats.getBalance());
        assertEquals(0, stats.getSavingsRate());
        assertTrue(stats.getCategoryData().isEmpty());
        assertTrue(stats.getMonthlyData().isEmpty());
    }

    @Test
    void monthlyStatsIgnorePendingAndUnpostedTransactions() {
        when(transactions.findByUserIdOrderByDateDesc(7)).thenReturn(List.of(
                transaction("expense", 50_000, "completed", "COP", 4L, 11),
                transaction("expense", 20_000, "pending", "COP", 4L, 11),
                transaction("income", 400_000, "completed", "COP", null, 10)
        ));
        var monthly = service.getMonthlyStats(7);
        assertEquals(2, monthly.size());
        assertEquals("2026-10", monthly.get(0).getMonth());
        assertEquals("2026-11", monthly.get(1).getMonth());
    }
}
