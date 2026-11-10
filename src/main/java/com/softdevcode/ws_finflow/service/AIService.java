package com.softdevcode.ws_finflow.service;

import com.softdevcode.ws_finflow.dto.AIAnalysisResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.List;
import java.util.Map;

@Service
public class AIService {

    private final RestTemplate restTemplate;
    private final String       aiUrl;

    public AIService(
            RestTemplateBuilder builder,
            @Value("${ai.service.url:http://localhost:8000}") String aiUrl) {
        // Prevent an unavailable upstream AI service from occupying server threads indefinitely.
        this.restTemplate = builder
                .setConnectTimeout(Duration.ofSeconds(3))
                .setReadTimeout(Duration.ofSeconds(15))
                .build();
        this.aiUrl        = aiUrl;
    }

    // ── Análisis completo ─────────────────────────────────────────────
    public AIAnalysisResponse getAnalisis(Integer userId) {
        HttpEntity<Map<String, Object>> req = jsonRequest(Map.of("usuario_id", userId));
        ResponseEntity<AIAnalysisResponse> res = restTemplate.exchange(
                aiUrl + "/analisis-completo",
                HttpMethod.POST, req, AIAnalysisResponse.class);
        return res.getBody();
    }

    // ── Meta de ahorro ────────────────────────────────────────────────
    public Map<?, ?> getMetaAhorro(Integer userId, String descripcion, Double monto) {
        HttpEntity<Map<String, Object>> req = jsonRequest(Map.of(
                "usuario_id",       userId,
                "meta_descripcion", descripcion,
                "meta_monto",       monto
        ));
        return restTemplate.exchange(
                aiUrl + "/meta-ahorro",
                HttpMethod.POST, req, Map.class).getBody();
    }

    // ── Chat financiero ───────────────────────────────────────────────
    public Map<?, ?> chat(Integer userId, String mensaje) {
        HttpEntity<Map<String, Object>> req = jsonRequest(Map.of(
                "usuario_id", userId,
                "mensaje",    mensaje,
                "historial",  List.of(),
                "stream",     false
        ));
        return restTemplate.exchange(
                aiUrl + "/chat",
                HttpMethod.POST, req, Map.class).getBody();
    }

    // ── Health check ──────────────────────────────────────────────────
    public Map<?, ?> health() {
        return restTemplate.getForObject(aiUrl + "/health", Map.class);
    }

    // ── Helper ────────────────────────────────────────────────────────
    private <T> HttpEntity<T> jsonRequest(T body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, headers);
    }
}