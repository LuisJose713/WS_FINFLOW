package com.softdevcode.ws_finflow.controller;

import com.softdevcode.ws_finflow.entity.User;
import com.softdevcode.ws_finflow.repository.UserRepository;
import com.softdevcode.ws_finflow.security.VaultEnvelopeValidator;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

/**
 * Opaque E2EE persistence. No method in this controller accepts an encryption key
 * or financial plaintext, and the server never decrypts saved records.
 *
 * Experimental endpoint: keep out of production until every client performs
 * authenticated AES-256-GCM encryption and the legacy plaintext migration is verified.
 */
@RestController
@ConditionalOnProperty(name = "finflow.private-vault.enabled", havingValue = "true")
@RequestMapping("/private-vault/entries")
@RequiredArgsConstructor
public class PrivateVaultController {
    private final JdbcTemplate jdbc;
    private final UserRepository users;

    public record Entry(UUID id, long revision, String nonce, String ciphertext, Instant updatedAt) {}
    public record PutRequest(String nonce, String ciphertext, Long expectedRevision) {}

    private long actor(Authentication auth) {
        if (auth == null || !auth.isAuthenticated() || auth.getName() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        return users.findByEmail(auth.getName()).map(User::getId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
    }

    private Entry readRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Entry((UUID) rs.getObject("entry_id"), rs.getLong("revision"),
                Base64.getUrlEncoder().withoutPadding().encodeToString(rs.getBytes("nonce")),
                Base64.getUrlEncoder().withoutPadding().encodeToString(rs.getBytes("ciphertext")),
                rs.getTimestamp("updated_at").toInstant());
    }

    @GetMapping
    @Transactional(readOnly = true)
    public List<Entry> list(Authentication authentication,
                            @RequestParam(required = false) UUID after,
                            @RequestParam(defaultValue = "100") int limit) {
        long uid = actor(authentication);
        if (limit < 1 || limit > 250) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Límite inválido");
        }
        if (after == null) {
            return jdbc.query("""
                SELECT entry_id,nonce,ciphertext,revision,updated_at
                  FROM private_vault_entries WHERE user_id=?
                 ORDER BY entry_id LIMIT ?
                """, (rs, row) -> readRow(rs), uid, limit);
        }
        return jdbc.query("""
            SELECT entry_id,nonce,ciphertext,revision,updated_at
              FROM private_vault_entries WHERE user_id=? AND entry_id>?
             ORDER BY entry_id LIMIT ?
            """, (rs, row) -> readRow(rs), uid, after, limit);
    }

    @PutMapping("/{id}")
    @Transactional
    public Entry put(Authentication authentication, @PathVariable UUID id,
                     @RequestBody PutRequest request) {
        long uid = actor(authentication);
        if (request == null || request.expectedRevision() == null || request.expectedRevision() < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Revisión requerida");
        }
        VaultEnvelopeValidator.Envelope envelope =
                VaultEnvelopeValidator.decode(request.nonce(), request.ciphertext());
        // expectedRevision=0 means INSERT ONLY. Any positive revision means
        // conditional UPDATE ONLY. No last-write-wins on simultaneous devices.
        List<Entry> updated;
        if (request.expectedRevision() == 0) {
            updated = jdbc.query("""
                INSERT INTO private_vault_entries(user_id,entry_id,nonce,ciphertext)
                VALUES (?,?,?,?)
                ON CONFLICT (user_id,entry_id) DO NOTHING
                RETURNING entry_id,nonce,ciphertext,revision,updated_at
                """, (rs, row) -> readRow(rs), uid, id, envelope.nonce(), envelope.ciphertext());
        } else {
            updated = jdbc.query("""
                UPDATE private_vault_entries
                   SET nonce=?,ciphertext=?,revision=revision+1,updated_at=now()
                 WHERE user_id=? AND entry_id=? AND revision=?
                RETURNING entry_id,nonce,ciphertext,revision,updated_at
                """, (rs, row) -> readRow(rs),
                    envelope.nonce(), envelope.ciphertext(), uid, id, request.expectedRevision());
        }
        if (updated.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Conflicto de sincronización: descarga y combina los cambios en el dispositivo");
        }
        return updated.get(0);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void delete(Authentication authentication, @PathVariable UUID id,
                       @RequestParam long expectedRevision) {
        long uid = actor(authentication);
        if (expectedRevision < 1 || jdbc.update("""
                DELETE FROM private_vault_entries WHERE user_id=? AND entry_id=? AND revision=?
                """, uid, id, expectedRevision) != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Conflicto de sincronización: la versión cambió o el registro no existe");
        }
    }
}
