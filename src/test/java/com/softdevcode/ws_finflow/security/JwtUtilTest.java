package com.softdevcode.ws_finflow.security;

import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.*;

class JwtUtilTest {
    private static final String KEY = "test-only-never-deploy-strong-unique-secret-2026";

    @Test
    void rejectsWeakSigningKey() {
        assertThrows(IllegalArgumentException.class, () -> new JwtUtil("weak"));
        assertThrows(IllegalArgumentException.class, () -> new JwtUtil(""));
    }

    @Test
    void validatesSignedTokenAndRejectsDifferentKey() throws Exception {
        JwtUtil one = new JwtUtil(KEY);
        String token = one.generateToken("owner@example.com", false);
        assertEquals("owner@example.com", one.validateTokenAndGetEmail(token));
        JwtUtil other = new JwtUtil("another-test-only-unique-secret-at-least-32-bytes");
        assertThrows(Exception.class, () -> other.validateTokenAndGetEmail(token));
    }

    @Test
    void includesTokenVersionForServerSideRevocation() throws Exception {
        JwtUtil jwt = new JwtUtil(KEY);
        String token = jwt.generateToken("owner@example.com", false, 7);
        assertEquals(7, jwt.validateToken(token).version());
        assertEquals("owner@example.com", jwt.validateToken(token).email());
        assertEquals(0, jwt.validateToken(jwt.generateToken("owner@example.com", false)).version());
    }

    @Test
    void usesFiveHoursForWebAndThirtyDaysForMobile() {
        JwtUtil jwt = new JwtUtil(KEY);
        Date webExpiration = Jwts.parserBuilder()
                .setSigningKey(KEY.getBytes(StandardCharsets.UTF_8))
                .build().parseClaimsJws(jwt.generateToken("owner@example.com", false))
                .getBody().getExpiration();
        Date mobileExpiration = Jwts.parserBuilder()
                .setSigningKey(KEY.getBytes(StandardCharsets.UTF_8))
                .build().parseClaimsJws(jwt.generateToken("owner@example.com", true))
                .getBody().getExpiration();
        long delta = mobileExpiration.getTime() - webExpiration.getTime();
        assertTrue(delta > java.time.Duration.ofDays(29).toMillis());
        assertTrue(delta < java.time.Duration.ofDays(31).toMillis());
    }
}
