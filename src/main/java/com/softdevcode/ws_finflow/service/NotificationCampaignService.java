package com.softdevcode.ws_finflow.service;

import com.softdevcode.ws_finflow.security.FieldCipher;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Durable one-time messages. Clients cannot choose another user's inbox. */
@Service
public class NotificationCampaignService {
    private final JdbcTemplate jdbc;
    private final FieldCipher cipher;
    private final PushReminderService push;
    public record Schedule(String title, String body, String audience, OffsetDateTime scheduledAt) {}
    public record Campaign(UUID id, String title, String body, String audience, Instant scheduledAt,
                           String status, int acceptedWeb, int failedWeb, int nativeDevices) {}
    public record Device(String deviceId, String platform, boolean enabled) {}
    public record Message(UUID id, String title, String body, Instant scheduledAt) {}

    public NotificationCampaignService(JdbcTemplate jdbc, FieldCipher cipher, PushReminderService push) {
        this.jdbc = jdbc; this.cipher = cipher; this.push = push;
    }

    public Map<String, Object> capabilities() {
        return Map.of("webReady", push.configured(), "timezone", "America/Bogota",
            "webSubscriptions", jdbc.queryForObject("SELECT COUNT(*) FROM finflow_push_subscriptions", Long.class),
            "nativeDevices", jdbc.queryForObject("SELECT COUNT(*) FROM finflow_notification_devices WHERE enabled", Long.class));
    }

    public List<Campaign> list() {
        return jdbc.query("SELECT * FROM finflow_notification_campaigns ORDER BY created_at DESC LIMIT 50", this::campaign);
    }

    public Campaign create(long owner, Schedule request) {
        if (request == null || request.title() == null || request.body() == null || request.scheduledAt() == null)
            throw invalid("Completa el título, el mensaje y la fecha de envío.");
        String title = request.title().strip();
        String body = request.body().strip();
        Instant scheduled = request.scheduledAt().toInstant();
        Instant now = Instant.now();
        if (title.isEmpty() || title.length() > 80 || body.isEmpty() || body.length() > 320)
            throw invalid("El título admite 80 caracteres y el mensaje 320.");
        if (request.audience() == null || !List.of("SELF", "ALL").contains(request.audience())) throw invalid("Selecciona los destinatarios.");
        if (scheduled.isBefore(now.plusSeconds(30)) || scheduled.isAfter(now.plus(366, ChronoUnit.DAYS)))
            throw invalid("Elige una fecha futura, con al menos 30 segundos de margen y dentro del próximo año.");
        UUID id = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO finflow_notification_campaigns(id,created_by,title_cipher,body_cipher,audience,scheduled_at)
            VALUES (?,?,?,?,?,?)
            """, id, owner, cipher.encrypt("notification_campaigns.title", title),
            cipher.encrypt("notification_campaigns.body", body), request.audience(), Timestamp.from(scheduled));
        return new Campaign(id, title, body, request.audience(), scheduled, "SCHEDULED", 0, 0, 0);
    }

    public void cancel(UUID id) {
        if (jdbc.update("UPDATE finflow_notification_campaigns SET status='CANCELLED',finished_at=NOW() WHERE id=? AND status='SCHEDULED'", id) != 1)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "El mensaje ya se procesó o no está programado.");
    }

    @Scheduled(fixedDelayString = "${FINFLOW_CAMPAIGN_INTERVAL_MS:30000}", initialDelay = 30000)
    public void dispatchDue() {
        // A failed process is explicit rather than silently duplicating an uncertain push.
        jdbc.update("UPDATE finflow_notification_campaigns SET status='FAILED',finished_at=NOW() WHERE status='SENDING' AND scheduled_at < NOW() - INTERVAL '30 minutes'");
        List<UUID> due = jdbc.queryForList("SELECT id FROM finflow_notification_campaigns WHERE status='SCHEDULED' AND scheduled_at<=NOW() ORDER BY scheduled_at LIMIT 10", UUID.class);
        for (UUID id : due) {
            int claimed = jdbc.update("UPDATE finflow_notification_campaigns SET status='SENDING' WHERE id=? AND status='SCHEDULED' AND scheduled_at<=NOW()", id);
            if (claimed != 1) continue;
            try {
                var rows = jdbc.query("SELECT * FROM finflow_notification_campaigns WHERE id=?", this::campaign, id);
                Campaign message = rows.getFirst();
                Long creator = jdbc.queryForObject("SELECT created_by FROM finflow_notification_campaigns WHERE id=?", Long.class, id);
                Long only = "SELF".equals(message.audience()) ? creator : null;
                var delivered = push.sendMessage(only, message.title(), message.body(), "finflow-message-" + id, "/dashboard");
                Integer nativeCount = only == null
                    ? jdbc.queryForObject("SELECT COUNT(*) FROM finflow_notification_devices WHERE enabled", Integer.class)
                    : jdbc.queryForObject("SELECT COUNT(*) FROM finflow_notification_devices WHERE enabled AND user_id=?", Integer.class, only);
                jdbc.update("UPDATE finflow_notification_campaigns SET status='SENT',finished_at=NOW(),accepted_web=?,failed_web=?,native_devices=? WHERE id=?",
                    delivered.accepted(), delivered.failed(), nativeCount == null ? 0 : nativeCount, id);
            } catch (Exception ex) {
                jdbc.update("UPDATE finflow_notification_campaigns SET status='FAILED',finished_at=NOW() WHERE id=?", id);
            }
        }
    }

    public void device(long owner, Device request) {
        if (request == null || request.platform() == null || !List.of("ANDROID", "IOS").contains(request.platform())) throw invalid("Dispositivo inválido.");
        String device = deviceHash(request.deviceId());
        jdbc.update("""
            INSERT INTO finflow_notification_devices(user_id,device_hash,platform,enabled)
            VALUES (?,?,?,?) ON CONFLICT(user_id,device_hash) DO UPDATE SET
              platform=EXCLUDED.platform, enabled=EXCLUDED.enabled, updated_at=NOW(),
              subscribed_at=CASE WHEN EXCLUDED.enabled AND NOT finflow_notification_devices.enabled
                  THEN NOW() ELSE finflow_notification_devices.subscribed_at END
            """, owner, device, request.platform(), request.enabled());
    }

    public List<Message> feed(long owner, String deviceId) {
        return jdbc.query("""
            SELECT c.id,c.title_cipher,c.body_cipher,c.scheduled_at
            FROM finflow_notification_campaigns c
            JOIN finflow_notification_devices d ON d.user_id=? AND d.device_hash=? AND d.enabled
            WHERE c.status IN ('SENDING','SENT') AND c.scheduled_at<=NOW()
              AND c.scheduled_at>=GREATEST(d.subscribed_at,NOW()-INTERVAL '7 days')
              AND (c.audience='ALL' OR c.created_by=?)
              AND NOT EXISTS (SELECT 1 FROM finflow_notification_receipts r
                WHERE r.campaign_id=c.id AND r.user_id=d.user_id AND r.device_hash=d.device_hash)
            ORDER BY c.scheduled_at LIMIT 20
            """, (rs, index) -> new Message(rs.getObject("id", UUID.class),
                cipher.decrypt("notification_campaigns.title", rs.getString("title_cipher")),
                cipher.decrypt("notification_campaigns.body", rs.getString("body_cipher")), rs.getTimestamp("scheduled_at").toInstant()),
            owner, deviceHash(deviceId), owner);
    }

    public void acknowledge(long owner, String deviceId, UUID id) {
        jdbc.update("""
            INSERT INTO finflow_notification_receipts(campaign_id,user_id,device_hash)
            SELECT c.id,d.user_id,d.device_hash FROM finflow_notification_campaigns c
            JOIN finflow_notification_devices d ON d.user_id=? AND d.device_hash=? AND d.enabled
            WHERE c.id=? AND c.status IN ('SENDING','SENT') AND c.scheduled_at<=NOW()
              AND c.scheduled_at>=GREATEST(d.subscribed_at,NOW()-INTERVAL '7 days')
              AND (c.audience='ALL' OR c.created_by=?) ON CONFLICT DO NOTHING
            """, owner, deviceHash(deviceId), id, owner);
    }

    private String deviceHash(String id) {
        try { return cipher.lookup("notification_devices.id", UUID.fromString(id).toString()); }
        catch (RuntimeException ex) { throw invalid("Dispositivo inválido."); }
    }
    private Campaign campaign(ResultSet rs, int index) throws SQLException {
        return new Campaign(rs.getObject("id", UUID.class),
            cipher.decrypt("notification_campaigns.title", rs.getString("title_cipher")),
            cipher.decrypt("notification_campaigns.body", rs.getString("body_cipher")),
            rs.getString("audience"), rs.getTimestamp("scheduled_at").toInstant(), rs.getString("status"),
            rs.getInt("accepted_web"), rs.getInt("failed_web"), rs.getInt("native_devices"));
    }
    private ResponseStatusException invalid(String reason) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason); }
}
