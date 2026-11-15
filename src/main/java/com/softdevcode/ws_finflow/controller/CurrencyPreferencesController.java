package com.softdevcode.ws_finflow.controller;

import com.softdevcode.ws_finflow.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Per-account preferred currency; the authoritative owner comes from JWT.
 * This preference never converts database amounts or changes ledger currency.
 * Uses the existing user_ui_preferences table, avoiding a risky migration.
 */
@RestController
@RequestMapping("/preferences/currency")
public class CurrencyPreferencesController {
    private static final Set<String> SUPPORTED = Set.of("COP", "USD", "EUR", "GBP", "MXN", "BRL", "CAD");
    private final JdbcTemplate jdbc;
    private final UserRepository users;

    public CurrencyPreferencesController(JdbcTemplate jdbc, UserRepository users) {
        this.jdbc = jdbc;
        this.users = users;
    }

    public record CurrencyUpdate(String currency) {}

    @GetMapping
    public Map<String, String> read(Authentication authentication) {
        Long owner = owner(authentication);
        List<String> entries = jdbc.queryForList(
            "SELECT card_id FROM user_ui_preferences " +
            "WHERE user_id = ? AND card_id LIKE 'currency.%' AND hidden = FALSE ORDER BY card_id",
            String.class, owner
        );
        String selected = entries.stream()
            .map(s -> s.substring("currency.".length()))
            .filter(SUPPORTED::contains)
            .findFirst()
            .orElse("COP");
        return Map.of("currency", selected);
    }

    @PutMapping
    @Transactional
    public Map<String, String> update(Authentication authentication, @RequestBody CurrencyUpdate request) {
        Long owner = owner(authentication);
        String selected = request == null || request.currency() == null
                ? "" : request.currency().trim().toUpperCase(java.util.Locale.ROOT);
        if (!SUPPORTED.contains(selected))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Moneda no admitida");

        // Serialize concurrent writes for this user so there is one default.
        jdbc.queryForObject("SELECT id FROM users WHERE id = ? FOR UPDATE", Long.class, owner);
        jdbc.update("DELETE FROM user_ui_preferences WHERE user_id = ? AND card_id LIKE 'currency.%'", owner);
        if (!"COP".equals(selected))
            jdbc.update("INSERT INTO user_ui_preferences(user_id, card_id, hidden) VALUES (?, ?, FALSE)",
                owner, "currency." + selected);
        return Map.of("currency", selected);
    }

    private Long owner(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated())
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        return users.findByEmail(authentication.getName()).orElseThrow(() ->
            new ResponseStatusException(HttpStatus.UNAUTHORIZED)).getId();
    }
}
