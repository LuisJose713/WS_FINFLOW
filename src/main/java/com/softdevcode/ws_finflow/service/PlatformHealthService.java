package com.softdevcode.ws_finflow.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Read-only, admin-only diagnostics: no PII, DB credentials or internal URLs
 * are returned to the browser. Health checks are cached to avoid flooding
 * Railway, Neon or the configured AI provider on dashboard refreshes.
 */
@Service
public class PlatformHealthService {
    private static final Duration TTL = Duration.ofSeconds(30);
    private static final String CANONICAL_WEB = "https://finflow-opal-five.vercel.app";
    private final JdbcTemplate jdbc;
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    private final String aiServiceUrl;

    private Instant cachedAt = Instant.EPOCH;
    private Snapshot cached;

    public record Check(String key, String name, String status, Long latencyMs, String detail) {}
    public record Snapshot(String overall, String checkedAt, List<Check> services) {}

    public PlatformHealthService(JdbcTemplate jdbc,
            @Value("${AI_SERVICE_URL:}") String aiServiceUrl) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.aiServiceUrl = aiServiceUrl == null ? "" : aiServiceUrl.trim();
    }

    public synchronized Snapshot getSnapshot() {
        Instant now = Instant.now();
        if (cached != null && now.isBefore(cachedAt.plus(TTL))) return cached;

        List<Check> checks = List.of(
            new Check("api", "API · Railway", "UP", 0L, "Servicio respondiendo"),
            probeDatabase(),
            probeHttp("web", "Web · Vercel", CANONICAL_WEB + "/login"),
            aiServiceUrl.isBlank()
                ? new Check("ai", "Asesor IA", "NOT_CONFIGURED", null, "Sin proveedor configurado")
                : probeHttp("ai", "Asesor IA", aiServiceUrl.replaceAll("/+$", "") + "/health")
        );
        String overall = checks.stream().allMatch(c -> "UP".equals(c.status())) ? "UP" : "DEGRADED";
        cached = new Snapshot(overall, now.toString(), checks);
        cachedAt = now;
        return cached;
    }

    private Check probeDatabase() {
        long started = System.nanoTime();
        try {
            Integer result = jdbc.query(connection -> {
                var statement = connection.prepareStatement("SELECT 1");
                statement.setQueryTimeout(2);
                return statement;
            }, rs -> rs.next() ? rs.getInt(1) : 0);
            boolean ok = Integer.valueOf(1).equals(result);
            return new Check("database", "Base de datos · PostgreSQL", ok ? "UP" : "DOWN",
                    elapsed(started), ok ? "Consulta de verificación correcta" : "Respuesta inesperada");
        } catch (Exception ex) {
            return new Check("database", "Base de datos · PostgreSQL", "DOWN", elapsed(started),
                    "No responde a la verificación");
        }
    }

    private Check probeHttp(String key, String name, String url) {
        final URI uri;
        try {
            uri = URI.create(url);
            if (uri.getHost() == null ||
                (!"https".equalsIgnoreCase(uri.getScheme()) &&
                 !"http".equalsIgnoreCase(uri.getScheme())) ||
                 uri.getUserInfo() != null || uri.getFragment() != null) {
                return new Check(key, name, "NOT_CONFIGURED", null, "Dirección de servicio inválida");
            }
            // The public website is pinned. Do not allow a configurable URL
            // supplied by HTTP parameters; this is not a general URL fetcher.
            if ("web".equals(key) && !CANONICAL_WEB.equals(uri.getScheme() + "://" + uri.getAuthority()))
                return new Check(key, name, "NOT_CONFIGURED", null, "Origen web inesperado");
        } catch (IllegalArgumentException ex) {
            return new Check(key, name, "NOT_CONFIGURED", null, "Dirección de servicio inválida");
        }

        long started = System.nanoTime();
        try {
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(3))
                    .header("Accept", "text/html, application/json")
                    .GET().build();
            HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
            boolean ok = response.statusCode() >= 200 && response.statusCode() < 300;
            return new Check(key, name, ok ? "UP" : "DOWN", elapsed(started),
                    ok ? "Responde correctamente" : "Respuesta HTTP no satisfactoria");
        } catch (Exception ex) {
            if (ex instanceof InterruptedException) Thread.currentThread().interrupt();
            return new Check(key, name, "DOWN", elapsed(started), "No responde al chequeo");
        }
    }

    private static long elapsed(long started) {
        return Duration.ofNanos(System.nanoTime() - started).toMillis();
    }
}
