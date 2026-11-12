package com.softdevcode.ws_finflow.controller;

import com.softdevcode.ws_finflow.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.Map;

/** Presentation choices only. Identity is always resolved from the verified session. */
@RestController
@RequestMapping("/preferences/ui")
public class UiPreferencesController {
    private final JdbcTemplate jdbc;
    private final UserRepository users;
    public UiPreferencesController(JdbcTemplate jdbc, UserRepository users) { this.jdbc = jdbc; this.users = users; }
    public record CardVisibility(Boolean hidden) {}

    @GetMapping
    public Map<String, Object> read(Authentication authentication) {
        return Map.of("hiddenCards", jdbc.queryForList(
            "SELECT card_id FROM user_ui_preferences WHERE user_id = ? AND hidden = TRUE ORDER BY card_id",
            String.class, userId(authentication)));
    }

    @PutMapping("/{cardId}")
    public Map<String, Object> update(Authentication authentication, @PathVariable String cardId, @RequestBody CardVisibility body) {
        if (body == null || body.hidden() == null || !cardId.matches("[a-z][a-z0-9.-]{0,72}\\.intro"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Preferencia inválida");
        jdbc.update("""
            INSERT INTO user_ui_preferences(user_id, card_id, hidden) VALUES (?, ?, ?)
            ON CONFLICT (user_id, card_id) DO UPDATE SET hidden = EXCLUDED.hidden
            """, userId(authentication), cardId, body.hidden());
        return Map.of("cardId", cardId, "hidden", body.hidden());
    }

    private Long userId(Authentication authentication) {
        if (authentication == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        return users.findByEmail(authentication.getName()).orElseThrow(() ->
            new ResponseStatusException(HttpStatus.UNAUTHORIZED)).getId();
    }
}
