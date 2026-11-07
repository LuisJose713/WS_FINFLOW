package com.softdevcode.ws_finflow.controller;

import com.softdevcode.ws_finflow.dto.LoginRequest;
import com.softdevcode.ws_finflow.dto.RegisterRequest;
import com.softdevcode.ws_finflow.entity.User;
import com.softdevcode.ws_finflow.repository.UserRepository;
import com.softdevcode.ws_finflow.security.JwtUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
    @Mock com.softdevcode.ws_finflow.security.AuthRateLimiter limiter;
    @InjectMocks AuthController controller;

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
        request.setPrivacyAccepted(true);
        request.setPrivacyVersion("2026-11-07-v1");
        org.springframework.test.util.ReflectionTestUtils.setField(controller,"privacyControllerName","FinFlow test");
        org.springframework.test.util.ReflectionTestUtils.setField(controller,"privacyContactEmail","test@example.com");
        org.springframework.test.util.ReflectionTestUtils.setField(controller,"privacyContactAddress","Calle 1, Colombia");
        org.springframework.test.util.ReflectionTestUtils.setField(controller,"privacyContactPhone","3000000000");
        when(users.save(any(User.class))).thenAnswer(invocation -> {
            User saved = invocation.getArgument(0);
            saved.setId(99L);
            return saved;
        });
        when(jwt.generateToken("tester@example.com", false, 0)).thenReturn("short-lived-jwt");

        ResponseEntity<?> response = controller.register(request, "mobile");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(jwt).generateToken("tester@example.com", false, 0);
        verify(jwt, never()).generateToken(anyString(), eq(true), anyInt());
    }
}
