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
    void tokenRemainsValidAcrossServiceRecreationWithSameSecret() throws Exception {
        JwtUtil beforeDeployment = new JwtUtil(KEY);
        String existingSession = beforeDeployment.generateToken("owner@example.com", false, 3);

        // Simulate a new application instance after deploying an unrelated feature.
        JwtUtil afterDeployment = new JwtUtil(KEY);
        assertEquals("owner@example.com", afterDeployment.validateToken(existingSession).email());
        assertEquals(3, afterDeployment.validateToken(existingSession).version());
    }

    @Test
    void acceptsPreviouslyIssuedTokenWithoutVersionClaim() throws Exception {
        String legacyToken = Jwts.builder()
                .setSubject("owner@example.com")
                .setIssuedAt(new Date(System.currentTimeMillis() - 1000))
                .setExpiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith(io.jsonwebtoken.security.Keys.hmacShaKeyFor(KEY.getBytes(StandardCharsets.UTF_8)),
                        io.jsonwebtoken.SignatureAlgorithm.HS256)
                .compact();

        JwtUtil afterDeployment = new JwtUtil(KEY);
        assertEquals("owner@example.com", afterDeployment.validateToken(legacyToken).email());
        assertEquals(0, afterDeployment.validateToken(legacyToken).version());
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
