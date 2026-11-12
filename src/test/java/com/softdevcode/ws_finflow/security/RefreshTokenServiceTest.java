package com.softdevcode.ws_finflow.security;

import com.softdevcode.ws_finflow.entity.User;
import com.softdevcode.ws_finflow.repository.UserRepository;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.util.Map;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Transactional
class RefreshTokenServiceTest {
    @Autowired RefreshTokenService sessions;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate jdbc;
    private User user;

    @BeforeEach void createUser() {
        user = new User();
        user.setEmail(UUID.randomUUID() + "@example.invalid");
        user.setUsername("Session test " + UUID.randomUUID());
        user.setPassword("not-a-real-password-hash");
        user = users.saveAndFlush(user);
    }

    @Test void rawRefreshCredentialsAreNeverStoredAndRotationToleratesConcurrentTabs() {
        String first = sessions.issue(user);
        assertEquals(43, first.length());
        String stored = jdbc.queryForObject("SELECT token_hash FROM auth_refresh_sessions WHERE user_id = ?", String.class, user.getId());
        assertEquals(64, stored.length());
        assertNotEquals(first, stored);
        Map<String, Object> renewed = sessions.refresh(first);
        String next = (String) renewed.get("refreshToken");
        assertNotEquals(first, next);
        assertEquals(next, sessions.refresh(first).get("refreshToken"));
        assertNotNull(renewed.get("token"));
    }

    @Test void oldCredentialReuseRevokesThatDeviceWithoutRevokingAnotherDevice() {
        String first = sessions.issue(user);
        String anotherDevice = sessions.issue(user);
        String next = (String) sessions.refresh(first).get("refreshToken");
        jdbc.update("UPDATE auth_refresh_sessions SET revoked_at = CURRENT_TIMESTAMP - INTERVAL '20 seconds' WHERE token_hash = ?", RefreshTokenService.hash(first));
        assertThrows(ResponseStatusException.class, () -> sessions.refresh(first));
        assertThrows(ResponseStatusException.class, () -> sessions.refresh(next));
        assertNotNull(sessions.refresh(anotherDevice).get("token"));
    }

    @Test void passwordChangesAndExplicitLogoutPreventRenewal() {
        String first = sessions.issue(user);
        user.setTokenVersion(1); users.saveAndFlush(user);
        assertThrows(ResponseStatusException.class, () -> sessions.refresh(first));
        String second = sessions.issue(user);
        sessions.logout(second);
        assertThrows(ResponseStatusException.class, () -> sessions.refresh(second));
    }

    @Test void expiredAndMalformedCredentialsCannotRenew() {
        String first = sessions.issue(user);
        jdbc.update("UPDATE auth_refresh_sessions SET expires_at = CURRENT_TIMESTAMP - INTERVAL '1 second' WHERE token_hash = ?", RefreshTokenService.hash(first));
        assertThrows(ResponseStatusException.class, () -> sessions.refresh(first));
        assertThrows(ResponseStatusException.class, () -> sessions.refresh("fake"));
    }
}
