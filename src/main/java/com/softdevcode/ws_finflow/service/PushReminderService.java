package com.softdevcode.ws_finflow.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.softdevcode.ws_finflow.repository.UserRepository;
import com.softdevcode.ws_finflow.security.FieldCipher;
import jakarta.annotation.PostConstruct;
import nl.martijndwars.webpush.Notification;
import nl.martijndwars.webpush.PushService;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.security.Security;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class PushReminderService {
    private static final ZoneId COLOMBIA = ZoneId.of("America/Bogota");
    private final JdbcTemplate jdbc;
    private final UserRepository users;
    private final ObjectMapper mapper;
    private final FieldCipher cipher;
    @Value("${FINFLOW_VAPID_PUBLIC_KEY:}") private String publicKey = "";
    @Value("${FINFLOW_VAPID_PRIVATE_KEY:}") private String privateKey = "";
    @Value("${FINFLOW_VAPID_SUBJECT:mailto:notifications@finflow.app}") private String subject;
    public record DeliveryResult(int accepted, int failed) {}
    private record Subscription(String endpoint, String key, String auth) {}

    public PushReminderService(JdbcTemplate jdbc, UserRepository users, ObjectMapper mapper, FieldCipher cipher) {
        this.jdbc = jdbc; this.users = users; this.mapper = mapper; this.cipher = cipher;
    }
    @PostConstruct
    public void configureCrypto() {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null)
            Security.addProvider(new BouncyCastleProvider());
    }
    public boolean configured() { return !publicKey.isBlank() && !privateKey.isBlank(); }
    public String publicKey() { return configured() ? publicKey : ""; }

    public long ownerId(String email) {
        if (email == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        return users.findByEmail(email).orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED)).getId();
    }

    static boolean trustedEndpoint(String endpoint) {
        try {
            URI uri = URI.create(endpoint);
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            return "https".equals(uri.getScheme()) && uri.getUserInfo() == null && uri.getFragment() == null
                && (uri.getPort() == -1 || uri.getPort() == 443)
                && (host.equals("fcm.googleapis.com") || host.equals("updates.push.services.mozilla.com")
                    || host.equals("web.push.apple.com") || host.endsWith(".push.apple.com")
                    || host.endsWith(".notify.windows.com"));
        } catch (RuntimeException ex) { return false; }
    }

    public void subscribe(long userId, String endpoint, String p256dh, String auth) {
        if (!configured()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Web Push sin configurar");
        boolean valid = endpoint != null && endpoint.length() <= 2048 && trustedEndpoint(endpoint)
            && p256dh != null && p256dh.length() <= 128 && auth != null && auth.length() <= 64;
        if (!valid) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Suscripción inválida");
        try {
            byte[] key = Base64.getUrlDecoder().decode(p256dh);
            valid &= key.length == 65 && key[0] == 4 && Base64.getUrlDecoder().decode(auth).length == 16;
        } catch (RuntimeException ex) { valid = false; }
        if (!valid) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Suscripción inválida");
        jdbc.update("""
            INSERT INTO finflow_push_subscriptions(user_id,endpoint,endpoint_cipher,p256dh,auth)
            VALUES (?,?,?,?,?) ON CONFLICT(endpoint) DO UPDATE SET user_id=EXCLUDED.user_id,
              endpoint_cipher=EXCLUDED.endpoint_cipher,p256dh=EXCLUDED.p256dh,auth=EXCLUDED.auth
            """, userId, cipher.lookup("finflow_push_subscriptions.endpoint", endpoint),
            cipher.encrypt("finflow_push_subscriptions.endpoint", endpoint),
            cipher.encrypt("finflow_push_subscriptions.p256dh", p256dh), cipher.encrypt("finflow_push_subscriptions.auth", auth));
    }

    public void unsubscribe(long userId, String endpoint) {
        jdbc.update("DELETE FROM finflow_push_subscriptions WHERE user_id=? AND endpoint=?", userId,
            cipher.lookup("finflow_push_subscriptions.endpoint", endpoint));
    }
    @Scheduled(cron = "0 0 10 * * *", zone = "America/Bogota")
    public void morning() { daily(10); }
    @Scheduled(cron = "0 0 16 * * *", zone = "America/Bogota")
    public void afternoon() { daily(16); }
    public int sendTest(long user) {
        return sendMessage(user, "FinFlow · Notificaciones activadas", "Esta es tu notificación de prueba.", "finflow-test", "/dashboard").accepted();
    }
    private void daily(int hour) {
        if (!configured()) return;
        List<Long> owners = jdbc.queryForList("SELECT DISTINCT user_id FROM finflow_push_subscriptions", Long.class);
        for (Long owner : owners) {
            int claimed = jdbc.update("INSERT INTO finflow_push_delivery_slots(user_id,delivery_day,delivery_hour) VALUES (?,?,?) ON CONFLICT DO NOTHING",
                owner, LocalDate.now(COLOMBIA), hour);
            if (claimed == 0) continue;
            sendMessage(owner, hour == 16 ? "FinFlow · Revisa tus gastos de hoy" : "FinFlow · Es hora de registrar tus movimientos",
                hour == 16 ? "¿Cuánto has gastado hoy? Revisa tus movimientos y toma el control."
                    : "Mantén tus finanzas al día. Registra tus ingresos y gastos.", "finflow-daily-" + hour, "/transactions");
        }
    }

    public DeliveryResult sendMessage(Long onlyUser, String title, String body, String tag, String url) {
        if (!configured()) return new DeliveryResult(0, 0);
        String sql = "SELECT endpoint_cipher,p256dh,auth FROM finflow_push_subscriptions" + (onlyUser == null ? "" : " WHERE user_id=?");
        // Read before network I/O so a slow push service does not hold a database connection.
        List<Subscription> subscriptions = jdbc.query(sql, (rs, index) -> new Subscription(
            cipher.decrypt("finflow_push_subscriptions.endpoint", rs.getString(1)),
            cipher.decrypt("finflow_push_subscriptions.p256dh", rs.getString(2)),
            cipher.decrypt("finflow_push_subscriptions.auth", rs.getString(3))), onlyUser == null ? new Object[]{} : new Object[]{onlyUser});
        int accepted = 0, failed = 0;
        for (var subscription : subscriptions) {
            if (!trustedEndpoint(subscription.endpoint())) { failed++; continue; }
            try {
                String payload = mapper.writeValueAsString(Map.of("title", title, "body", body, "url", url, "tag", tag));
                var service = new PushService(publicKey, privateKey, subject);
                var response = service.send(new Notification(subscription.endpoint(), subscription.key(), subscription.auth(), payload));
                try {
                    int status = response.getStatusLine().getStatusCode();
                    if (status >= 200 && status < 300) accepted++;
                    else {
                        failed++;
                        if (status == 404 || status == 410) jdbc.update("DELETE FROM finflow_push_subscriptions WHERE endpoint=?",
                            cipher.lookup("finflow_push_subscriptions.endpoint", subscription.endpoint()));
                    }
                } finally {
                    org.apache.http.util.EntityUtils.consume(response.getEntity());
                    if (response instanceof java.io.Closeable closeable) closeable.close();
                }
            } catch (Exception ex) { failed++; }
        }
        return new DeliveryResult(accepted, failed);
    }
}
