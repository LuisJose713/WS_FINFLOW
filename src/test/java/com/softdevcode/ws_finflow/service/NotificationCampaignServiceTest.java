package com.softdevcode.ws_finflow.service;

import com.softdevcode.ws_finflow.security.FieldCipher;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class NotificationCampaignServiceTest {
    final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    final PushReminderService push = mock(PushReminderService.class);
    final FieldCipher cipher = new FieldCipher(Base64.getEncoder().encodeToString(new byte[32]), true);
    final NotificationCampaignService service = new NotificationCampaignService(jdbc, cipher, push);

    @Test void messageContentIsEncryptedBeforeItReachesTheDatabase() {
        var result = service.create(7, new NotificationCampaignService.Schedule(" Novedad ", " Mensaje privado ", "SELF", OffsetDateTime.now().plusHours(1)));
        var title = ArgumentCaptor.forClass(String.class);
        var body = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(contains("INSERT INTO finflow_notification_campaigns"), any(UUID.class), eq(7L), title.capture(), body.capture(), eq("SELF"), any(Timestamp.class));
        assertTrue(title.getValue().startsWith(FieldCipher.PREFIX));
        assertTrue(body.getValue().startsWith(FieldCipher.PREFIX));
        assertEquals("Novedad", cipher.decrypt("notification_campaigns.title", title.getValue()));
        assertEquals("Mensaje privado", cipher.decrypt("notification_campaigns.body", body.getValue()));
        assertEquals("SCHEDULED", result.status());
        verifyNoInteractions(push);
    }
    @Test void invalidSchedulesCannotWriteOrSendAnything() {
        for (var request : new NotificationCampaignService.Schedule[]{null,
            new NotificationCampaignService.Schedule("", "Mensaje", "SELF", OffsetDateTime.now().plusHours(1)),
            new NotificationCampaignService.Schedule("Título", "Mensaje", null, OffsetDateTime.now().plusHours(1)),
            new NotificationCampaignService.Schedule("Título", "Mensaje", "ALL", OffsetDateTime.now().minusMinutes(1)),
            new NotificationCampaignService.Schedule("Título", "Mensaje", "ALL", OffsetDateTime.now().plusYears(2))}) {
            assertThrows(ResponseStatusException.class, () -> service.create(7, request));
        }
        verifyNoInteractions(jdbc, push);
    }
    @Test void deviceIdsAndPlatformsAreValidatedBeforePersistence() {
        assertThrows(ResponseStatusException.class, () -> service.device(7, new NotificationCampaignService.Device("bad", "ANDROID", true)));
        assertThrows(ResponseStatusException.class, () -> service.device(7, new NotificationCampaignService.Device(UUID.randomUUID().toString(), null, true)));
        verifyNoInteractions(jdbc);
    }
}
