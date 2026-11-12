package com.softdevcode.ws_finflow.controller;

import com.softdevcode.ws_finflow.entity.User;
import com.softdevcode.ws_finflow.repository.UserRepository;
import com.softdevcode.ws_finflow.security.AuthRateLimiter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AccountDeletionControllerTest {
    @Mock UserRepository users;
    @Mock JdbcTemplate jdbc;
    @Mock AuthRateLimiter limiter;
    @InjectMocks AuthController controller;

    private User user() {
        User user = new User();
        user.setId(55L);
        user.setEmail("owner@example.com");
        user.setPassword(new BCryptPasswordEncoder().encode("correct-secret"));
        return user;
    }

    private UsernamePasswordAuthenticationToken principal() {
        return new UsernamePasswordAuthenticationToken("owner@example.com", null);
    }

    @Test void refusesAnonymousDeletionBeforeDatabaseAccess() {
        assertEquals(HttpStatus.UNAUTHORIZED,
            controller.deleteAccount(null, new AuthController.ConfirmPasswordRequest("correct-secret")).getStatusCode());
        verifyNoInteractions(users, jdbc);
    }

    @Test void wrongPasswordNeverCallsDeleteFunction() {
        when(users.findByEmail("owner@example.com")).thenReturn(Optional.of(user()));
        assertEquals(HttpStatus.FORBIDDEN,
            controller.deleteAccount(principal(), new AuthController.ConfirmPasswordRequest("wrong-secret")).getStatusCode());
        verify(limiter).recordFailure("owner@example.com");
        verifyNoInteractions(jdbc);
    }

    @Test void refusesEmptyAndOverlongPasswords() {
        when(users.findByEmail("owner@example.com")).thenReturn(Optional.of(user()));
        assertEquals(HttpStatus.BAD_REQUEST,
            controller.deleteAccount(principal(), new AuthController.ConfirmPasswordRequest("")).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST,
            controller.deleteAccount(principal(), new AuthController.ConfirmPasswordRequest("é".repeat(37))).getStatusCode());
        verifyNoInteractions(jdbc);
    }

    @Test void correctPasswordPassesOnlyAuthenticatedAccountIdToDatabase() {
        when(users.findByEmail("owner@example.com")).thenReturn(Optional.of(user()));
        when(jdbc.queryForObject("SELECT public.finflow_delete_account(?)", Boolean.class, 55L)).thenReturn(true);
        assertEquals(HttpStatus.NO_CONTENT,
            controller.deleteAccount(principal(), new AuthController.ConfirmPasswordRequest("correct-secret")).getStatusCode());
        verify(jdbc, times(1)).queryForObject("SELECT public.finflow_delete_account(?)", Boolean.class, 55L);
        verify(limiter).clear("owner@example.com");
    }

    @Test void lockedAccountCannotDeleteUntilRateLimitExpires() {
        User user = user();
        when(users.findByEmail("owner@example.com")).thenReturn(Optional.of(user));
        when(limiter.isLocked("owner@example.com")).thenReturn(true);
        assertEquals(HttpStatus.TOO_MANY_REQUESTS,
            controller.deleteAccount(principal(), new AuthController.ConfirmPasswordRequest("correct-secret")).getStatusCode());
        verifyNoInteractions(jdbc);
    }
}
