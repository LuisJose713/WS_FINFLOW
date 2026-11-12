package com.softdevcode.ws_finflow.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.softdevcode.ws_finflow.repository.UserRepository;
import nl.martijndwars.webpush.Notification;
import nl.martijndwars.webpush.PushService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;

@Service
public class PushReminderService {
    private static final String OWNER = "stpptx@icloud.com";
    private static final ZoneId COLOMBIA = ZoneId.of("America/Bogota");
    private final JdbcTemplate jdbc;
    private final UserRepository users;
    private final ObjectMapper mapper;
    @Value("${FINFLOW_VAPID_PUBLIC_KEY:}") private String publicKey;
    @Value("${FINFLOW_VAPID_PRIVATE_KEY:}") private String privateKey;
    @Value("${FINFLOW_VAPID_SUBJECT:mailto:notifications@finflow.app}") private String subject;

    public PushReminderService(JdbcTemplate jdbc, UserRepository users, ObjectMapper mapper) {
        this.jdbc = jdbc; this.users = users; this.mapper = mapper;
    }

    public boolean configured() { return !publicKey.isBlank() && !privateKey.isBlank(); }
    public String publicKey() { return configured() ? publicKey : ""; }

    public long ownerId(String email) {
        if (email == null || !OWNER.equalsIgnoreCase(email))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Notificaciones privadas");
        return users.findByEmail(email).orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED)).getId();
    }

    public void subscribe(long userId, String endpoint, String p256dh, String auth) {
        if (!configured()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Web Push sin configurar");
        if (endpoint == null || endpoint.length() > 2048 || !endpoint.startsWith("https://")
                || p256dh == null || p256dh.length() > 256 || auth == null || auth.length() > 256)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Suscripción inválida");
        jdbc.update("""
            INSERT INTO finflow_push_subscriptions(user_id, endpoint, p256dh, auth)
            VALUES (?, ?, ?, ?)
            ON CONFLICT (endpoint) DO UPDATE SET user_id = EXCLUDED.user_id,
              p256dh = EXCLUDED.p256dh, auth = EXCLUDED.auth
            """, userId, endpoint, p256dh, auth);
    }

    public void unsubscribe(long userId, String endpoint) {
        jdbc.update("DELETE FROM finflow_push_subscriptions WHERE user_id = ? AND endpoint = ?", userId, endpoint);
    }

    @Scheduled(cron = "0 0 10 * * *", zone = "America/Bogota")
    public void daily() { send(false); }

    public int send(boolean test) {
        if (!configured()) return 0;
        long ownerId = ownerId(OWNER);
        LocalDate day = LocalDate.now(COLOMBIA);
        if (!test) {
            int claimed = jdbc.update("""
                INSERT INTO finflow_push_delivery_days(user_id, delivery_day)
                VALUES (?, ?) ON CONFLICT DO NOTHING
                """, ownerId, day);
            if (claimed == 0) return 0;
        }
        int[] sent = {0};
        jdbc.query("SELECT endpoint, p256dh, auth FROM finflow_push_subscriptions WHERE user_id = ?",
            rs -> {
                String endpoint = rs.getString(1);
                try {
                    var push = new PushService(publicKey, privateKey, subject);
                    String payload = mapper.writeValueAsString(Map.of(
                        "title", "FinFlow · Es hora de registrar tus movimientos 💰",
                        "body", "Mantén tus finanzas al día. Registra tus ingresos y gastos.",
                        "url", "/transactions"));
                    var response = push.send(new Notification(endpoint, rs.getString(2), rs.getString(3), payload));
                    if (response.getStatusLine().getStatusCode() >= 200 && response.getStatusLine().getStatusCode() < 300) sent[0]++;
                    else if (response.getStatusLine().getStatusCode() == 404 || response.getStatusLine().getStatusCode() == 410)
                        jdbc.update("DELETE FROM finflow_push_subscriptions WHERE endpoint = ?", endpoint);
                } catch (Exception ex) {
                    // Failed deliveries must not expose subscription secrets in logs.
                    System.err.println("FinFlow Web Push delivery failed: " + ex.getClass().getSimpleName());
                }
            }, ownerId);
        return sent[0];
    }
}
