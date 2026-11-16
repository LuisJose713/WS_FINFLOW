package com.softdevcode.ws_finflow.service;

import com.softdevcode.ws_finflow.entity.User;
import com.softdevcode.ws_finflow.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import java.time.OffsetDateTime;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest
@Transactional
class NotificationCampaignIntegrationTest {
    @Autowired NotificationCampaignService service;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserRepository users;
    @MockBean PushReminderService push;
    long owner, other;
    final String device = UUID.randomUUID().toString();
    @BeforeEach void setup() {
        owner = user("owner"); other = user("other");
        when(push.sendMessage(any(), anyString(), anyString(), anyString(), anyString()))
            .thenReturn(new PushReminderService.DeliveryResult(2, 1));
        service.device(owner, new NotificationCampaignService.Device(device, "ANDROID", true));
        service.device(other, new NotificationCampaignService.Device(device, "ANDROID", true));
        jdbc.update("UPDATE finflow_notification_devices SET subscribed_at=NOW()-INTERVAL '1 hour' WHERE user_id IN (?,?)", owner, other);
    }
    long user(String prefix) {
        var user = new User();
        user.setUsername(prefix + UUID.randomUUID()); user.setEmail(prefix + UUID.randomUUID() + "@example.invalid");
        user.setPassword("test-only-password"); user.setRole("USER");
        return users.saveAndFlush(user).getId();
    }
    UUID due(String audience) {
        UUID id = service.create(owner, new NotificationCampaignService.Schedule("Título", "Mensaje", audience, OffsetDateTime.now().plusHours(1))).id();
        jdbc.update("UPDATE finflow_notification_campaigns SET scheduled_at=NOW()-INTERVAL '1 minute' WHERE id=?", id);
        return id;
    }
    @Test void selfMessagesStayPrivateAndReceiptsAreDeviceAndOwnerScoped() {
        UUID id = due("SELF"); service.dispatchDue();
        assertEquals(1, service.feed(owner, device).size());
        assertTrue(service.feed(other, device).isEmpty());
        service.acknowledge(other, device, id);
        assertEquals(1, service.feed(owner, device).size());
        service.acknowledge(owner, device, id);
        assertTrue(service.feed(owner, device).isEmpty());
    }
    @Test void aDueCampaignIsClaimedOnlyOnceAndOptOutStopsNativeDelivery() {
        UUID id = due("ALL"); service.dispatchDue(); service.dispatchDue();
        verify(push, times(1)).sendMessage(isNull(), eq("Título"), eq("Mensaje"), anyString(), eq("/dashboard"));
        assertEquals(1, service.feed(other, device).size());
        service.device(other, new NotificationCampaignService.Device(device, "ANDROID", false));
        assertTrue(service.feed(other, device).isEmpty());
        var result = service.list().stream().filter(c -> c.id().equals(id)).findFirst().orElseThrow();
        assertEquals("SENT", result.status()); assertEquals(2, result.acceptedWeb());
    }
    @Test void aCancelledCampaignCannotBeDispatched() {
        UUID id = due("ALL"); service.cancel(id); service.dispatchDue();
        verifyNoInteractions(push);
        assertTrue(service.feed(owner, device).isEmpty());
    }
}
