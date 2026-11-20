package com.softdevcode.ws_finflow.sync;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FinanceChangeLogTest {
    @Mock JdbcTemplate jdbc;
    @InjectMocks FinanceChangeLog log;
    @Test void writesOnlyOwnerRevisionAndResource() {
        when(jdbc.queryForObject(anyString(), eq(Long.class), eq(7))).thenReturn(3L);
        log.record(7, "transactions");
        verify(jdbc).update(contains("finflow_sync_changes"), eq(7), eq(3L), eq("transactions"));
    }
    @Test void rejectsUnboundedOrInvalidCursors() {
        assertEquals(HttpStatus.BAD_REQUEST,
            assertThrows(ResponseStatusException.class, () -> log.changes(7,-1,50)).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST,
            assertThrows(ResponseStatusException.class, () -> log.changes(7,0,101)).getStatusCode());
        verifyNoInteractions(jdbc);
    }
    @Test void neverWritesUnrecognizedResources() {
        assertThrows(IllegalArgumentException.class, () -> log.record(7,"private-data"));
        verifyNoInteractions(jdbc);
    }
}
