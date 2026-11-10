package com.softdevcode.ws_finflow.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthIpRateLimiterTest {
    @Mock JdbcTemplate jdbc;
    @InjectMocks AuthIpRateLimiter limiter;

    @Test void limitsAWholeIpAcrossAllAccountsAndKeepsPseudonymsInDatabase() {
        when(jdbc.queryForObject(anyString(), eq(Integer.class), anyString()))
                .thenReturn(59, 60, 61);
        assertTrue(limiter.allow("login", "192.0.2.15", 60));
        assertTrue(limiter.allow("login", "192.0.2.15", 60));
        assertFalse(limiter.allow("login", "192.0.2.15", 60));
        ArgumentCaptor<String> hashes = ArgumentCaptor.forClass(String.class);
        verify(jdbc, times(3)).queryForObject(anyString(), eq(Integer.class), hashes.capture());
        assertEquals(1, hashes.getAllValues().stream().distinct().count());
        assertEquals(64, hashes.getValue().length());
        assertFalse(hashes.getValue().contains("192.0.2.15"));
    }

    @Test void keepsRegisterAndLoginBucketsSeparate() {
        when(jdbc.queryForObject(anyString(), eq(Integer.class), anyString())).thenReturn(1);
        assertTrue(limiter.allow("login", "203.0.113.1", 60));
        assertTrue(limiter.allow("register", "203.0.113.1", 20));
        ArgumentCaptor<String> hashes = ArgumentCaptor.forClass(String.class);
        verify(jdbc, times(2)).queryForObject(anyString(), eq(Integer.class), hashes.capture());
        assertNotEquals(hashes.getAllValues().get(0), hashes.getAllValues().get(1));
    }

    @Test void rejectsUnexpectedEndpointAndLimit() {
        assertThrows(IllegalArgumentException.class, () -> limiter.allow("admin", "127.0.0.1", 20));
        assertThrows(IllegalArgumentException.class, () -> limiter.allow("login", "127.0.0.1", 0));
        verifyNoInteractions(jdbc);
    }
}
