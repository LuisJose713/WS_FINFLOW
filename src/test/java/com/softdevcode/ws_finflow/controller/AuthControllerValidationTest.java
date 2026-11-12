package com.softdevcode.ws_finflow.controller;

import com.softdevcode.ws_finflow.dto.LoginRequest;
import com.softdevcode.ws_finflow.dto.RegisterRequest;
import com.softdevcode.ws_finflow.entity.User;
import com.softdevcode.ws_finflow.repository.UserRepository;
import com.softdevcode.ws_finflow.security.JwtUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthControllerValidationTest {
    @Mock UserRepository users;
    @Mock JwtUtil jwt;
    @Mock org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Mock com.softdevcode.ws_finflow.security.RefreshTokenService refreshTokens;
    @Mock com.softdevcode.ws_finflow.config.PrivacyPolicySettings privacySettings;
    @Mock com.softdevcode.ws_finflow.security.AuthRateLimiter limiter;
    @InjectMocks AuthController controller;

    @org.junit.jupiter.api.BeforeEach
    void refreshCredential() {
        lenient().when(refreshTokens.issue(any(User.class))).thenReturn("test-refresh-credential");
    }

    @Test
    void rejectsInvalidLoginBeforeDatabaseLookup() {
        LoginRequest request = new LoginRequest();
        request.setEmail("");
        request.setPassword("");
        ResponseEntity<?> response = controller.login(request, "web");
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        verifyNoInteractions(users, jwt);
    }

    @Test
    void avoidsUserEnumerationOnLogin() {
        LoginRequest request = new LoginRequest();
        request.setEmail("missing@example.com");
        request.setPassword("something");
        when(users.findByEmail("missing@example.com")).thenReturn(Optional.empty());
        ResponseEntity<?> response = controller.login(request, "web");
        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        assertEquals("Credenciales inválidas", ((Map<?, ?>) response.getBody()).get("error"));
        verifyNoInteractions(jwt);
    }

    @Test
    void rejectsWeakPasswordBeforeAccountCreation() {
        RegisterRequest request = new RegisterRequest();
        request.setName("Tester");
        request.setEmail("tester@example.com");
        request.setPassword("1234567");
        ResponseEntity<?> response = controller.register(request, "web");
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        verifyNoInteractions(users, jwt);
    }

    @Test
    void ignoresSpoofedMobileHeaderDuringPublicRegistration() {
        RegisterRequest request = new RegisterRequest();
        request.setName("Tester");
        request.setEmail("tester@example.com");
        request.setPassword("long-enough-password");
        when(users.save(any(User.class))).thenAnswer(invocation -> {
            User saved = invocation.getArgument(0);
            saved.setId(99L);
            return saved;
        });
        when(jwt.generateToken("tester@example.com", false, 0)).thenReturn("short-lived-jwt");

        ResponseEntity<?> response = controller.register(request, "mobile");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(jwt).generateToken("tester@example.com", false, 0);
        verify(jdbc).update(contains("INSERT INTO billing_trials"), eq(99L),
                any(java.sql.Timestamp.class), any(java.sql.Timestamp.class));
        verify(jwt, never()).generateToken(anyString(), eq(true), anyInt());
    }

    @ParameterizedTest
    @ValueSource(strings = {"ascii", "unicode"})
    void rejectsPasswordsBeyondBcryptByteLimitBeforeRegistration(String variant) {
        RegisterRequest request = new RegisterRequest();
        request.setName("Tester");
        request.setEmail("tester@example.com");
        request.setPassword(variant.equals("ascii") ? "a".repeat(73) : "é".repeat(37));

        ResponseEntity<?> response = controller.register(request, "web");

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertTrue(((Map<?, ?>) response.getBody()).get("error").toString().contains("72 bytes"));
        verifyNoInteractions(users, jwt, jdbc);
    }

    @Test
    void rejectsOverlongLoginBeforePasswordCheckInsteadOfTruncating() {
        LoginRequest request = new LoginRequest();
        request.setEmail("tester@example.com");
        request.setPassword("é".repeat(37));

        ResponseEntity<?> response = controller.login(request, "web");

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        verifyNoInteractions(users, jwt, limiter);
    }

    @Test
    void acceptsExactly72Utf8BytesForNewPasswords() {
        RegisterRequest request = new RegisterRequest();
        request.setName("Tester");
        request.setEmail("tester@example.com");
        request.setPassword("é".repeat(36));
        when(users.save(any(User.class))).thenAnswer(invocation -> {
            User saved = invocation.getArgument(0);
            saved.setId(99L);
            return saved;
        });
        when(jwt.generateToken("tester@example.com", false, 0)).thenReturn("test-token");

        assertEquals(HttpStatus.OK, controller.register(request, "web").getStatusCode());
        verify(users).save(any(User.class));
    }

    @Test
    void rejectsOverlongNewPasswordWithoutChangingHashOrTokenVersion() {
        User user = new User();
        user.setPassword("existing-hash");
        user.setTokenVersion(4);
        when(users.findByEmail("tester@example.com")).thenReturn(Optional.of(user));
        var auth = new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                "tester@example.com", null);
        String overlong = "é".repeat(37);

        ResponseEntity<?> response = controller.changePassword(auth,
                new AuthController.ChangePasswordRequest("current-password", overlong, overlong));

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("existing-hash", user.getPassword());
        assertEquals(4, user.getTokenVersion());
        verify(users, never()).save(any());
    }

    @Test
    void loginRejectsIgnoredSuffixesWithoutChangingExistingBcryptHashes() {
        var encoder = new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder();
        String password = "a".repeat(72);
        String hash = encoder.encode(password);
        User user = new User();
        user.setId(99L);
        user.setEmail("tester@example.com");
        user.setUsername("Tester");
        user.setPassword(hash);
        when(users.findByEmail(user.getEmail())).thenReturn(Optional.of(user));
        when(jwt.generateToken(user.getEmail(), false, 0)).thenReturn("test-token");
        LoginRequest request = new LoginRequest();
        request.setEmail(user.getEmail());
        request.setPassword(password);

        assertEquals(HttpStatus.OK, controller.login(request, "web").getStatusCode());
        request.setPassword(password + "different-suffix");
        assertEquals(HttpStatus.BAD_REQUEST, controller.login(request, "web").getStatusCode());
        assertEquals(hash, user.getPassword());
        verify(users, times(1)).findByEmail(user.getEmail());
        verify(jwt, times(1)).generateToken(user.getEmail(), false, 0);
    }
}
