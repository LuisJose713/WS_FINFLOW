package com.softdevcode.ws_finflow.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.softdevcode.ws_finflow.entity.User;
import com.softdevcode.ws_finflow.repository.UserRepository;
import com.softdevcode.ws_finflow.security.FieldCipher;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.security.Security;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PushReminderServiceTest {
    @Test void browserSubscriptionsCannotTargetInternalOrArbitraryServers() {
        for (String endpoint : new String[]{"https://localhost/private", "https://127.0.0.1", "https://fcm.googleapis.com.attacker.invalid/push", "https://fcm.googleapis.com:8443/push", "https://user@fcm.googleapis.com/push", "http://fcm.googleapis.com/push"})
            assertFalse(PushReminderService.trustedEndpoint(endpoint), endpoint);
        assertTrue(PushReminderService.trustedEndpoint("https://fcm.googleapis.com/fcm/send/test"));
        assertTrue(PushReminderService.trustedEndpoint("https://web.push.apple.com/test"));
    }
    @Test void everyAuthenticatedOwnerCanOptInAndCryptoProviderIsInstalled() {
        var users = mock(UserRepository.class);
        var owner = new User(); owner.setId(9L); owner.setEmail("another@example.invalid");
        when(users.findByEmail(owner.getEmail())).thenReturn(Optional.of(owner));
        var service = new PushReminderService(mock(JdbcTemplate.class), users, new ObjectMapper(), new FieldCipher("", false));
        service.configureCrypto();
        assertNotNull(Security.getProvider("BC"));
        assertEquals(9L, service.ownerId(owner.getEmail()));
    }
}
