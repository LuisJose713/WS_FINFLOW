package com.softdevcode.ws_finflow.security;

import com.softdevcode.ws_finflow.entity.User;
import com.softdevcode.ws_finflow.repository.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/** Rotating, per-device sessions. Password changes revoke them through token_version. */
@Service
public class RefreshTokenService {
    public static final Duration IDLE_LIFETIME = Duration.ofDays(90);
    private static final Duration CONCURRENT_REQUEST_GRACE = Duration.ofSeconds(10);
    private final JdbcTemplate jdbc;
    private final UserRepository users;
    private final JwtUtil jwt;
    private final byte[] rotationKey;
    private final SecureRandom random = new SecureRandom();

    public RefreshTokenService(JdbcTemplate jdbc, UserRepository users, JwtUtil jwt,
                               @Value("${JWT_SECRET}") String secret) {
        this.jdbc = jdbc; this.users = users; this.jwt = jwt;
        this.rotationKey = secret.getBytes(StandardCharsets.UTF_8);
    }

    @Transactional
    public String issue(User user) {
        byte[] bytes = new byte[32]; random.nextBytes(bytes);
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        insert(raw, UUID.randomUUID(), user, Instant.now().plus(IDLE_LIFETIME));
        return raw;
    }

    @Transactional(noRollbackFor = ResponseStatusException.class)
    public Map<String, Object> refresh(String raw) {
        if (raw == null || !raw.matches("[A-Za-z0-9_-]{43}")) throw unauthorized();
        var rows = jdbc.queryForList("SELECT * FROM auth_refresh_sessions WHERE token_hash = ? FOR UPDATE", hash(raw));
        if (rows.isEmpty()) throw unauthorized();
        var row = rows.getFirst();
        UUID family = (UUID) row.get("family_id");
        User user = users.findById(((Number) row.get("user_id")).longValue()).orElseThrow(RefreshTokenService::unauthorized);
        Instant now = Instant.now();
        if (((Timestamp) row.get("expires_at")).toInstant().isBefore(now) ||
            ((Number) row.get("token_version")).intValue() != version(user)) {
            revokeFamily(family); throw unauthorized();
        }
        String next = successor(raw);
        if (row.get("revoked_at") != null) {
            Instant revoked = ((Timestamp) row.get("revoked_at")).toInstant();
            if (row.get("replaced_by_hash") != null && revoked.plus(CONCURRENT_REQUEST_GRACE).isAfter(now)
                && hash(next).equals(row.get("replaced_by_hash"))) {
                Integer active = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM auth_refresh_sessions
                    WHERE token_hash = ? AND revoked_at IS NULL AND expires_at > CURRENT_TIMESTAMP
                    """, Integer.class, hash(next));
                if (active != null && active == 1) return response(user, next);
            }
            // Reuse outside the concurrency window revokes only this device's family.
            revokeFamily(family); throw unauthorized();
        }
        insert(next, family, user, now.plus(IDLE_LIFETIME));
        jdbc.update("UPDATE auth_refresh_sessions SET revoked_at = ?, replaced_by_hash = ? WHERE token_hash = ?",
            Timestamp.from(now), hash(next), hash(raw));
        return response(user, next);
    }

    @Transactional
    public void logout(String raw) {
        if (raw == null || !raw.matches("[A-Za-z0-9_-]{43}")) return;
        var rows = jdbc.queryForList("SELECT family_id FROM auth_refresh_sessions WHERE token_hash = ?", hash(raw));
        if (!rows.isEmpty()) revokeFamily((UUID) rows.getFirst().get("family_id"));
    }

    private void insert(String raw, UUID family, User user, Instant expires) {
        jdbc.update("""
            INSERT INTO auth_refresh_sessions(token_hash, family_id, user_id, token_version, expires_at)
            VALUES (?, ?, ?, ?, ?)
            """, hash(raw), family, user.getId(), version(user), Timestamp.from(expires));
    }

    private void revokeFamily(UUID family) {
        jdbc.update("UPDATE auth_refresh_sessions SET revoked_at = CURRENT_TIMESTAMP, replaced_by_hash = NULL WHERE family_id = ?", family);
    }

    private Map<String, Object> response(User user, String refresh) {
        return Map.of("token", jwt.generateToken(user.getEmail(), false, version(user)),
            "refreshToken", refresh, "user", Map.of("id", user.getId(), "name", user.getUsername(),
                "email", user.getEmail(), "role", user.getRole()));
    }

    private static int version(User user) { return user.getTokenVersion() == null ? 0 : user.getTokenVersion(); }

    static String hash(String raw) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException("Session digest unavailable", e); }
    }

    private String successor(String raw) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(rotationKey, "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                mac.doFinal(("finflow-refresh-v1:" + raw).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) { throw new IllegalStateException("Session rotation unavailable", e); }
    }

    private static ResponseStatusException unauthorized() {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sesión expirada o revocada");
    }
}
