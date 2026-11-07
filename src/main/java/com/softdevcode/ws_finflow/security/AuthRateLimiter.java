package com.softdevcode.ws_finflow.security;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class AuthRateLimiter {
    private final JdbcTemplate jdbc;

    public boolean isLocked(String email) {
        Boolean blocked=jdbc.queryForObject("""
            SELECT EXISTS(SELECT 1 FROM auth_rate_limits
              WHERE subject_hash=? AND locked_until > CURRENT_TIMESTAMP)
            """,Boolean.class,key(email));
        return Boolean.TRUE.equals(blocked);
    }

    public void recordFailure(String email) {
        jdbc.update("""
            INSERT INTO auth_rate_limits(subject_hash,window_started_at,failed_count,locked_until)
            VALUES(?,CURRENT_TIMESTAMP,1,NULL)
            ON CONFLICT(subject_hash) DO UPDATE SET
            failed_count=CASE WHEN auth_rate_limits.window_started_at<CURRENT_TIMESTAMP-INTERVAL '15 minutes'
                         THEN 1 ELSE auth_rate_limits.failed_count+1 END,
            locked_until=CASE
                WHEN auth_rate_limits.window_started_at<CURRENT_TIMESTAMP-INTERVAL '15 minutes' THEN NULL
                WHEN auth_rate_limits.failed_count+1>=8
                THEN CURRENT_TIMESTAMP+INTERVAL '10 minutes'
                ELSE auth_rate_limits.locked_until END,
            window_started_at=CASE WHEN auth_rate_limits.window_started_at<CURRENT_TIMESTAMP-INTERVAL '15 minutes'
                         THEN CURRENT_TIMESTAMP ELSE auth_rate_limits.window_started_at END
            """,key(email));
    }

    public void clear(String email) {
        jdbc.update("DELETE FROM auth_rate_limits WHERE subject_hash=?",key(email));
    }

    @Scheduled(fixedDelayString="${finflow.auth.cleanup-ms:86400000}",
               initialDelayString="${finflow.auth.cleanup-initial-ms:600000}")
    public void cleanup() {
        jdbc.update("DELETE FROM auth_rate_limits WHERE window_started_at<CURRENT_TIMESTAMP-INTERVAL '1 day'");
    }

    private String key(String email) {
        try {
            MessageDigest digest=MessageDigest.getInstance("SHA-256");
            byte[] bytes=digest.digest(email.trim().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch(NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable",ex);
        }
    }
}
