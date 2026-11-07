package com.softdevcode.ws_finflow.service;

import com.softdevcode.ws_finflow.dto.BudgetRequest;
import com.softdevcode.ws_finflow.dto.BudgetLimitRequest;
import com.softdevcode.ws_finflow.dto.BudgetOverview;
import com.softdevcode.ws_finflow.entity.Category;
import com.softdevcode.ws_finflow.entity.MonthlyBudget;
import com.softdevcode.ws_finflow.repository.CategoryRepository;
import com.softdevcode.ws_finflow.repository.MonthlyBudgetRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class MonthlyBudgetService {
    private final MonthlyBudgetRepository budgets;
    private final CategoryRepository categories;
    private final CategoryAccessValidator categoryValidator;
    private final JdbcTemplate jdbc;

    @Transactional(readOnly = true)
    public List<BudgetOverview> list(Integer userId, Integer year, Integer month) {
        YearMonth period = validPeriod(year, month);
        var mine = budgets.findByUserIdAndYearAndMonthOrderByIdAsc(
                userId, period.getYear(), period.getMonthValue());
        if (mine.isEmpty()) return List.of();

        // The backend aggregates actual, completed COP expenses for the current user.
        // No future planned expenses or other currencies are counted as spending.
        LocalDateTime start = period.atDay(1).atStartOfDay();
        LocalDateTime end = period.plusMonths(1).atDay(1).atStartOfDay();
        String sql = """
            SELECT category_id, COALESCE(ROUND(SUM(amount::numeric),2),0)::numeric AS spent
            FROM transactions
            WHERE user_id = ? AND type = 'expense'
              AND (status = 'completed' OR status IS NULL)
              AND (TRIM(COALESCE(currency, 'COP')) = 'COP')
              AND date >= ? AND date < ? AND category_id IS NOT NULL
            GROUP BY category_id
            """;
        List<Map<String, Object>> rows = jdbc.queryForList(sql,
                userId, Timestamp.valueOf(start), Timestamp.valueOf(end));
        Map<Long, BigDecimal> spentByCategory = new HashMap<>();
        for (var row : rows) {
            Object raw = row.get("spent");
            if (row.get("category_id") instanceof Number id && raw != null) {
                spentByCategory.put(id.longValue(), new BigDecimal(raw.toString()).setScale(2, RoundingMode.HALF_UP));
            }
        }
        Map<Long, Category> visibleCategories = categories.findVisibleTo(userId).stream()
                .collect(Collectors.toMap(Category::getId, Function.identity(), (first, second) -> first));
        return mine.stream().map(budget -> {
            BigDecimal spent = spentByCategory.getOrDefault(budget.getCategoryId(), BigDecimal.ZERO.setScale(2));
            BigDecimal limit = budget.getLimitAmount();
            BigDecimal remaining = limit.subtract(spent);
            // Floor the displayed percentage: 79.99% must not be labeled as 80%.
            BigDecimal used = spent.multiply(BigDecimal.valueOf(100))
                    .divide(limit, 0, RoundingMode.DOWN);
            int percent = used.min(BigDecimal.valueOf(9999)).intValue();
            // Use actual amounts, not a rounded display percentage, for alerts.
            String status = spent.compareTo(limit) > 0 ? "over"
                    : spent.compareTo(limit.multiply(new BigDecimal("0.80"))) >= 0 ? "near" : "ok";
            Category category = visibleCategories.get(budget.getCategoryId());
            return new BudgetOverview(budget.getId(), budget.getCategoryId(),
                    category == null ? "Categoría" : category.getName(),
                    budget.getYear(), budget.getMonth(), limit, spent,
                    remaining, percent, status, "COP");
        }).toList();
    }

    @Transactional
    public MonthlyBudget create(Integer userId, BudgetRequest request) {
        if (request == null || request.categoryId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Elige una categoría");
        }
        YearMonth period = validPeriod(request.year(), request.month());
        BigDecimal amount = validAmount(request.amount());
        categoryValidator.requireVisible(userId, request.categoryId(), "expense");

        if (budgets.existsByUserIdAndCategoryIdAndYearAndMonth(
                userId, request.categoryId(), period.getYear(), period.getMonthValue())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Ya existe un presupuesto para esa categoría y mes");
        }
        MonthlyBudget budget = new MonthlyBudget();
        budget.setUserId(userId);
        budget.setCategoryId(request.categoryId());
        budget.setYear(period.getYear());
        budget.setMonth(period.getMonthValue());
        budget.setLimitAmount(amount);
        return persist(budget);
    }

    @Transactional
    public MonthlyBudget changeLimit(Integer userId, Long id, BudgetLimitRequest request) {
        MonthlyBudget budget = owned(userId, id);
        if (request == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Indica el nuevo límite");
        budget.setLimitAmount(validAmount(request.amount()));
        return persist(budget);
    }

    @Transactional
    public void delete(Integer userId, Long id) {
        budgets.delete(owned(userId, id));
    }

    private MonthlyBudget owned(Integer userId, Long id) {
        return budgets.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Presupuesto no encontrado"));
    }

    private MonthlyBudget persist(MonthlyBudget budget) {
        try { return budgets.saveAndFlush(budget); }
        catch (DataIntegrityViolationException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "El presupuesto ya existe o no es válido", ex);
        }
    }

    private YearMonth validPeriod(Integer year, Integer month) {
        YearMonth now = YearMonth.now(ZoneOffset.UTC);
        int y = year == null ? now.getYear() : year;
        int m = month == null ? now.getMonthValue() : month;
        if (y < 2000 || y > 2100 || m < 1 || m > 12)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Año o mes inválido");
        return YearMonth.of(y, m);
    }

    private BigDecimal validAmount(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El límite debe ser positivo");
        try {
            BigDecimal exact = amount.setScale(2, RoundingMode.UNNECESSARY);
            if (exact.precision() > 15)
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Monto demasiado grande");
            return exact;
        } catch (ArithmeticException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Solo se admiten dos decimales");
        }
    }
}