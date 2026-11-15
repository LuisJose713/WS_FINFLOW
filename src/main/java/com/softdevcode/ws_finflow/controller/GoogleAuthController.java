package com.softdevcode.ws_finflow.controller;

import com.softdevcode.ws_finflow.entity.User;
import com.softdevcode.ws_finflow.repository.UserRepository;
import com.softdevcode.ws_finflow.security.GoogleIdTokenVerifier;
import com.softdevcode.ws_finflow.security.JwtUtil;
import com.softdevcode.ws_finflow.security.RefreshTokenService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

/**
 * Google login is available ONLY after explicit linking by the signed-in owner.
 * Never auto-link by Google email, even when email_verified=true.
 * Existing accounts retain their original user IDs, transactions and passwords.
 */
@RestController
@RequestMapping("/auth/google")
public class GoogleAuthController {
    private final GoogleIdTokenVerifier verifier;
    private final UserRepository users;
    private final JdbcTemplate jdbc;
    private final JwtUtil jwt;
    private final RefreshTokenService refreshTokens;

    public record GoogleCredential(String idToken) {}
    public record GoogleStatus(boolean linked, String email) {}

    public GoogleAuthController(GoogleIdTokenVerifier verifier, UserRepository users,
                                JdbcTemplate jdbc, JwtUtil jwt, RefreshTokenService refreshTokens) {
        this.verifier = verifier; this.users = users; this.jdbc = jdbc;
        this.jwt = jwt; this.refreshTokens = refreshTokens;
    }

    @GetMapping("/config")
    public Map<String, Object> config() {
        return Map.of("enabled", verifier.enabled(), "webClientId", verifier.webClientId());
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody GoogleCredential request) {
        var google = verifier.verify(request == null ? null : request.idToken());
        List<Long> ids = jdbc.queryForList(
            "SELECT user_id FROM public.google_account_links WHERE google_sub = ?",
            Long.class, google.sub());
        if (ids.isEmpty()) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                "code", "GOOGLE_LINK_REQUIRED",
                "error", "Primero inicia sesión con tu contraseña y vincula Google desde Seguridad. Si aún no tienes cuenta, regístrate."));
        }
        User user = users.findById(ids.getFirst()).orElseThrow(() ->
            new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Cuenta no disponible"));
        String token = jwt.generateToken(user.getEmail(), false, user.getTokenVersion() == null ? 0 : user.getTokenVersion());
        return ResponseEntity.ok(Map.of(
            "token", token, "refreshToken", refreshTokens.issue(user),
            "user", Map.of("id", user.getId(), "name", user.getUsername(),
                           "email", user.getEmail(), "role", user.getRole())));
    }

    @GetMapping("/status")
    public GoogleStatus status(Authentication authentication) {
        User user = owner(authentication);
        List<String> emails = jdbc.queryForList(
            "SELECT google_email FROM public.google_account_links WHERE user_id = ?",
            String.class, user.getId());
        return new GoogleStatus(!emails.isEmpty(), emails.isEmpty() ? "" : emails.getFirst());
    }

    @PostMapping("/link")
    @Transactional
    public GoogleStatus link(Authentication authentication, @RequestBody GoogleCredential request) {
        User user = owner(authentication);
        var google = verifier.verify(request == null ? null : request.idToken());
        if (!google.email().equalsIgnoreCase(user.getEmail())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "El correo de Google debe coincidir con el correo de tu cuenta FinFlow");
        }
        List<Long> holders = jdbc.queryForList(
            "SELECT user_id FROM public.google_account_links WHERE google_sub = ?", Long.class, google.sub());
        if (!holders.isEmpty() && !holders.getFirst().equals(user.getId()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Esta cuenta de Google ya está vinculada");
        List<String> current = jdbc.queryForList(
            "SELECT google_sub FROM public.google_account_links WHERE user_id = ?", String.class, user.getId());
        if (!current.isEmpty() && !current.getFirst().equals(google.sub()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Desvincula primero la cuenta de Google anterior");
        if (holders.isEmpty()) {
            jdbc.update("INSERT INTO public.google_account_links(google_sub,user_id,google_email) VALUES (?,?,?)",
                google.sub(), user.getId(), google.email());
        }
        return new GoogleStatus(true, google.email());
    }

    @DeleteMapping("/link")
    @Transactional
    public ResponseEntity<Void> unlink(Authentication authentication) {
        User user = owner(authentication);
        jdbc.update("DELETE FROM public.google_account_links WHERE user_id = ?", user.getId());
        return ResponseEntity.noContent().build();
    }

    private User owner(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated())
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sesión requerida");
        return users.findByEmail(authentication.getName()).orElseThrow(() ->
            new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Cuenta no disponible"));
    }
}
