package com.softdevcode.ws_finflow.security;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

/**
 * Atomic rate limiting shared across application replicas through PostgreSQL.
 * Source IPs are pseudonymized, not logged or stored in plaintext.
 *
 * The existing auth_rate_limits table is reused; no production DDL required.
 * This is a fixed-start 5-minute window, complemented by existing account
 * throttling and recommended CDN/edge controls.
 */
@Service
@RequiredArgsConstructor
public class AuthIpRateLimiter {
    private final JdbcTemplate jdbc;
    private static final String SQL = """
            INSERT INTO auth_rate_limits(subject_hash,window_started_at,failed_count,locked_until)
            VALUES (?, CURRENT_TIMESTAMP, 1, NULL)
            ON CONFLICT(subject_hash) DO UPDATE SET
                failed_count=CASE
                    WHEN auth_rate_limits.window_started_at < CURRENT_TIMESTAMP - INTERVAL '5 minutes'
                    THEN 1 ELSE auth_rate_limits.failed_count + 1 END,
                window_started_at=CASE
                    WHEN auth_rate_limits.window_started_at < CURRENT_TIMESTAMP - INTERVAL '5 minutes'
                    THEN CURRENT_TIMESTAMP ELSE auth_rate_limits.window_started_at END
            RETURNING failed_count
            """;

    /**
     * Every request counts, successful or not; avoids a credential-stuffing
     * attack that cycles through many different accounts from one address.
     */
    public boolean allow(String endpoint, String remoteIp, int maxAttempts) {
        if (!"login".equals(endpoint) && !"register".equals(endpoint)) {
            throw new IllegalArgumentException("Unsupported authentication endpoint");
        }
        if (maxAttempts < 1) throw new IllegalArgumentException("Invalid limit");
        String source = remoteIp == null || remoteIp.isBlank() ? "unknown" : remoteIp;
        Integer attempts = jdbc.queryForObject(SQL, Integer.class, key(endpoint, source));
        return attempts != null && attempts <= maxAttempts;
    }

    private String key(String endpoint, String ip) {
        try {
            byte[] data = ("finflow:ip:" + endpoint + ":" + ip.trim().toLowerCase(Locale.ROOT))
                    .getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
