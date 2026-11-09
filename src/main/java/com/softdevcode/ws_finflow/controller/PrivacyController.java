package com.softdevcode.ws_finflow.controller;

import com.softdevcode.ws_finflow.config.PrivacyPolicySettings;
import com.softdevcode.ws_finflow.entity.User;
import com.softdevcode.ws_finflow.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Data subject controls. A policy version is accepted only by an authenticated
 * user after explicit opt-in, never by a migration or an assumed legacy consent.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/privacy")
public class PrivacyController {
    private final JdbcTemplate jdbc;
    private final UserRepository users;
    private final PrivacyPolicySettings settings;

    public record ConsentRequest(Boolean accepted, String policyVersion, Boolean marketingConsent) {}
    public record MarketingRequest(Boolean marketingConsent) {}
    public record RightsRequest(String type, String description) {}

    @GetMapping("/policy")
    public Map<String, Object> policy() {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("version", PrivacyPolicySettings.VERSION);
        response.put("effectiveDate", PrivacyPolicySettings.EFFECTIVE_DATE);
        response.put("ready", settings.ready());
        response.put("controllerName", settings.controllerName());
        response.put("contactEmail", settings.contactEmail());
        response.put("contactAddress", settings.contactAddress());
        response.put("contactPhone", settings.contactPhone());
        response.put("rights", List.of("ACCESS", "RECTIFY", "DELETE", "REVOKE"));
        response.put("marketingOptional", true);
        return response;
    }

    @GetMapping("/status")
    public Map<String, Object> status(Authentication authentication) {
        User user = actor(authentication);
        List<Map<String, Object>> existing = jdbc.queryForList("""
            SELECT policy_version, accepted_at, marketing_opt_in
              FROM privacy_consents
             WHERE user_id = ? AND policy_version = ?
             ORDER BY accepted_at DESC LIMIT 1
            """, user.getId(), PrivacyPolicySettings.VERSION);
        boolean accepted = !existing.isEmpty();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("accepted", accepted);
        response.put("version", PrivacyPolicySettings.VERSION);
        response.put("policyReady", settings.ready());
        response.put("marketingConsent", accepted && Boolean.TRUE.equals(existing.get(0).get("marketing_opt_in")));
        if (accepted) response.put("acceptedAt", existing.get(0).get("accepted_at"));
        return response;
    }

    @PostMapping("/consent")
    @Transactional
    public Map<String, Object> consent(Authentication authentication,
                                      @RequestBody ConsentRequest request) {
        User user = actor(authentication);
        ensurePolicyPublished();
        if (request == null || !Boolean.TRUE.equals(request.accepted())
                || !PrivacyPolicySettings.VERSION.equals(request.policyVersion())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Debes aceptar expresamente la versión vigente de la política");
        }
        boolean marketing = Boolean.TRUE.equals(request.marketingConsent());
        // Never silently overwrite an earlier user's choice on repeated requests.
        int inserted = jdbc.update("""
                INSERT INTO privacy_consents(user_id, policy_version, marketing_opt_in, source)
                VALUES (?, ?, ?, 'LEGACY_LOGIN')
                ON CONFLICT (user_id, policy_version) DO NOTHING
                """, user.getId(), PrivacyPolicySettings.VERSION, marketing);
        if (inserted == 1) recordMarketingChoice(user.getId(), marketing, "CONSENT");
        return Map.of("accepted", true, "version", PrivacyPolicySettings.VERSION);
    }

    @PostMapping("/marketing")
    @Transactional
    public Map<String, Object> marketing(Authentication authentication,
                                         @RequestBody MarketingRequest request) {
        User user = actor(authentication);
        // Withdrawal remains available even when a publication flag is turned off.
        if (request == null || request.marketingConsent() == null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Elige si quieres comunicaciones comerciales");
        boolean choice = request.marketingConsent();
        int count = jdbc.update("""
              UPDATE privacy_consents SET marketing_opt_in = ?
               WHERE user_id = ? AND policy_version = ?
            """, choice, user.getId(), PrivacyPolicySettings.VERSION);
        if (count == 0) throw new ResponseStatusException(HttpStatus.CONFLICT, "Primero debes aceptar la política vigente");
        recordMarketingChoice(user.getId(), choice, "PREFERENCE");
        return Map.of("marketingConsent", choice, "saved", true);
    }

    @GetMapping("/receipt")
    public Map<String, Object> receipt(Authentication authentication) {
        User user = actor(authentication);
        List<Map<String, Object>> rows = jdbc.queryForList("""
             SELECT policy_version, marketing_opt_in, source, accepted_at
             FROM privacy_consents WHERE user_id = ? ORDER BY accepted_at DESC LIMIT 25
            """, user.getId());
        return Map.of("consents", rows);
    }

    @PostMapping("/rights-request")
    @Transactional
    public Map<String, Object> rightsRequest(Authentication authentication,
                                              @RequestBody RightsRequest request) {
        User user = actor(authentication);
        if (request == null || request.type() == null
                || !Set.of("ACCESS", "RECTIFY", "DELETE", "REVOKE").contains(request.type()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Tipo de solicitud inválido");
        String description = request.description() == null ? "" : request.description().trim();
        if (description.length() > 500)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Máximo 500 caracteres");
        Long id = jdbc.queryForObject("""
              INSERT INTO privacy_rights_requests(user_id, request_type, description)
              VALUES (?, ?, ?) RETURNING id
            """, Long.class, user.getId(), request.type(), description);
        return Map.of("id", id, "status", "PENDING",
               "message", "Solicitud registrada para revisión; no implica eliminación automática");
    }

    @GetMapping("/rights-requests")
    public List<Map<String, Object>> rightsRequests(Authentication authentication) {
        User user = actor(authentication);
        return jdbc.queryForList("""
             SELECT id, request_type, description, status, created_at
             FROM privacy_rights_requests WHERE user_id = ? ORDER BY created_at DESC LIMIT 50
            """, user.getId());
    }

    private void ensurePolicyPublished() {
        if (!settings.ready()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "La política aún no está legalmente publicada");
    }

    private void recordMarketingChoice(Integer userId, boolean optIn, String source) {
        jdbc.update("""
             INSERT INTO privacy_marketing_events(user_id, policy_version, marketing_opt_in, source)
             VALUES (?, ?, ?, ?)
            """, userId, PrivacyPolicySettings.VERSION, optIn, source);
    }

    private User actor(Authentication auth) {
        if (auth == null || !auth.isAuthenticated())
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Inicia sesión");
        return users.findByEmail(auth.getName()).orElseThrow(
                () -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sesión inválida"));
    }
}
