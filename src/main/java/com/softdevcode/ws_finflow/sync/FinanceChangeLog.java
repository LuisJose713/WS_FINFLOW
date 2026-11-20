package com.softdevcode.ws_finflow.sync;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.time.Instant;
import java.util.List;

/** Private change log. No financial values appear in events. */
@Service
@RequiredArgsConstructor
public class FinanceChangeLog {
    private final JdbcTemplate jdbc;
    public record Change(long revision, String resource, Instant at) {}
    public record Batch(long next, boolean hasMore, List<Change> changes) {}

    /** Changes and their revisions commit atomically with the owning write. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(Integer userId, String resource) {
        if (userId == null || !List.of("transactions", "accounts").contains(resource)) {
            throw new IllegalArgumentException("Invalid sync event");
        }
        Long revision = jdbc.queryForObject("""
            INSERT INTO finflow_sync_state(user_id, revision) VALUES (?, 1)
            ON CONFLICT (user_id) DO UPDATE
                SET revision = finflow_sync_state.revision + 1
            RETURNING revision
            """, Long.class, userId);
        if (revision == null) throw new IllegalStateException("No sync revision");
        jdbc.update("""
            INSERT INTO finflow_sync_changes(user_id, revision, resource) VALUES (?, ?, ?)
            """, userId, revision, resource);
    }

    @Transactional(readOnly = true)
    public long cursor(Integer userId) {
        List<Long> rows = jdbc.query(
                "SELECT revision FROM finflow_sync_state WHERE user_id = ?",
                (rs, rowNum) -> rs.getLong("revision"), userId);
        return rows.isEmpty() ? 0L : rows.get(0);
    }

    @Transactional(readOnly = true)
    public Batch changes(Integer userId, long after, int limit) {
        if (after < 0 || limit < 1 || limit > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cursor o límite inválido");
        }
        List<Change> rows = jdbc.query("""
            SELECT revision, resource, created_at
            FROM finflow_sync_changes
            WHERE user_id = ? AND revision > ?
            ORDER BY revision ASC LIMIT ?
            """,
                (rs, index) -> new Change(rs.getLong("revision"),
                        rs.getString("resource"), rs.getTimestamp("created_at").toInstant()),
                userId, after, limit + 1);
        boolean hasMore = rows.size() > limit;
        List<Change> page = hasMore ? rows.subList(0, limit) : rows;
        return new Batch(page.isEmpty() ? after : page.get(page.size()-1).revision(), hasMore, page);
    }
}
