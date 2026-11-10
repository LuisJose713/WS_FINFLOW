package com.softdevcode.ws_finflow.service;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.core.ResultSetExtractor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class PlatformHealthServiceTest {
    @SuppressWarnings("unchecked")
    @Test void redactsInternalEndpointsAndMarksUnconfiguredAi() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(any(PreparedStatementCreator.class), any(ResultSetExtractor.class))).thenReturn(1);
        PlatformHealthService health = new PlatformHealthService(jdbc, "");
        var response = health.getSnapshot();
        assertTrue(response.services().stream().anyMatch(s ->
                "ai".equals(s.key()) && "NOT_CONFIGURED".equals(s.status())));
        assertTrue(response.services().stream().anyMatch(s ->
                "database".equals(s.key()) && "UP".equals(s.status())));
        String serialized = response.toString();
        assertFalse(serialized.contains("postgresql://"));
        assertFalse(serialized.contains("JWT_SECRET"));
        health.getSnapshot();
        verify(jdbc, times(1)).query(any(PreparedStatementCreator.class), any(ResultSetExtractor.class));
    }

    @SuppressWarnings("unchecked")
    @Test void databaseFailureIsReportedAsDownWithoutExposingCause() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(any(PreparedStatementCreator.class), any(ResultSetExtractor.class)))
                .thenThrow(new IllegalStateException("database-secret-should-not-leak"));
        var response = new PlatformHealthService(jdbc, "").getSnapshot();
        var database = response.services().stream()
                .filter(c -> "database".equals(c.key())).findFirst().orElseThrow();
        assertEquals("DOWN", database.status());
        assertFalse(database.detail().contains("database-secret"));
        assertEquals("DEGRADED", response.overall());
    }
}
