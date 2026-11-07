package com.softdevcode.ws_finflow.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.time.Duration;
import java.util.Date;

@Component
public class JwtUtil {
    private static final long WEB_EXPIRATION = Duration.ofHours(5).toMillis();
    private static final long MOBILE_EXPIRATION = Duration.ofDays(30).toMillis();
    private final Key key;

    public JwtUtil(@Value("${JWT_SECRET}") String jwtSecret) {
        if (jwtSecret == null || jwtSecret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalArgumentException("JWT_SECRET debe tener al menos 32 bytes y configurarse fuera del repositorio");
        }
        this.key = Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8));
    }

    public String generateToken(String email, boolean longLived) {
        long now = System.currentTimeMillis();
        long expiration = longLived ? MOBILE_EXPIRATION : WEB_EXPIRATION;
        return Jwts.builder()
                .setSubject(email)
                .setIssuedAt(new Date(now))
                .setExpiration(new Date(now + expiration))
                .signWith(key, SignatureAlgorithm.HS256)
                .compact();
    }

    public String validateTokenAndGetEmail(String token) throws Exception {
        try {
            Claims claims = Jwts.parserBuilder()
                    .setSigningKey(key)
                    .build()
                    .parseClaimsJws(token)
                    .getBody();
            return claims.getSubject();
        } catch (Exception e) {
            throw new Exception("Token inválido o expirado");
        }
    }
}
