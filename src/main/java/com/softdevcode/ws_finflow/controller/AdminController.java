package com.softdevcode.ws_finflow.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/admin")
@RequiredArgsConstructor
public class AdminController {
    private final JdbcTemplate jdbc;
    private final com.softdevcode.ws_finflow.security.FieldCipher cipher;
    private record Activity(Long userId,java.time.LocalDateTime date) {}

    // SecurityConfig protects every /admin/** endpoint with ROLE_ADMIN.
    // Return aggregated usage metrics, never another user's financial records.
    @GetMapping("/verify")
    public Map<String, String> verify(Authentication auth) {
        return Map.of("status", "ok", "role", "ADMIN");
    }

    @GetMapping("/overview")
    public Map<String, Object> overview(Authentication auth) {
        Map<String, Object> result = new LinkedHashMap<>();
        var now=jdbc.queryForObject("SELECT LOCALTIMESTAMP",java.time.LocalDateTime.class);
        List<Activity> activity=jdbc.query("SELECT user_id,date FROM transactions WHERE date IS NOT NULL",
            (rs,i)->new Activity(rs.getLong("user_id"),java.time.LocalDateTime.parse(
                cipher.decrypt("transactions.date",rs.getString("date")).replace(' ','T'))));
        var recent=activity.stream().filter(a->!a.date().isBefore(now.minusDays(30))).toList();
        result.put("users", count("SELECT COUNT(*) FROM users"));
        result.put("newUsers30Days", count("SELECT COUNT(*) FROM users WHERE created_at >= CURRENT_TIMESTAMP - INTERVAL '30 days'"));
        result.put("activeUsers30Days", recent.stream().map(Activity::userId).distinct().count());
        result.put("transactions", count("SELECT COUNT(*) FROM transactions"));
        result.put("transactions30Days", (long)recent.size());
        result.put("incomeTransactions", count("SELECT COUNT(*) FROM transactions WHERE type = 'income'"));
        result.put("expenseTransactions", count("SELECT COUNT(*) FROM transactions WHERE type = 'expense'"));
        result.put("goals", count("SELECT COUNT(*) FROM goals"));
        result.put("plannedExpenses", count("SELECT COUNT(*) FROM planned_expenses"));
        result.put("pendingExpenses", count("SELECT COUNT(*) FROM planned_expenses WHERE status = 'pending'"));
        result.put("customCategories", count("SELECT COUNT(*) FROM categories WHERE user_id IS NOT NULL"));
        result.put("systemCategories", count("SELECT COUNT(*) FROM categories WHERE user_id IS NULL"));
        result.put("monthlyActivity", monthlyActivity(activity));
        return result;
    }

    private long count(String sql) {
        Long value = jdbc.queryForObject(sql, Long.class);
        return value == null ? 0 : value;
    }

    private List<Map<String, Object>> monthlyActivity(List<Activity> activity) {
        var result=jdbc.queryForList("""
            SELECT to_char(month, 'YYYY-MM') AS month,
                   (SELECT COUNT(*) FROM users u
                     WHERE u.created_at >= month
                       AND u.created_at < month + INTERVAL '1 month') AS signups
            FROM generate_series(
              date_trunc('month', CURRENT_DATE) - INTERVAL '5 months',
              date_trunc('month', CURRENT_DATE), INTERVAL '1 month') AS month
            ORDER BY month
            """);
        result.forEach(row -> row.put("transactions",activity.stream().filter(a ->
            java.time.YearMonth.from(a.date()).toString().equals(row.get("month"))).count()));
        return result;
    }
}
