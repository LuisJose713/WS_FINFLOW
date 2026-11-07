package com.softdevcode.ws_finflow.controller;

import com.softdevcode.ws_finflow.entity.User;
import com.softdevcode.ws_finflow.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;

/** A versioned and auditable consent, never silently backfilled for older accounts. */
@RestController
@RequiredArgsConstructor
@RequestMapping("/privacy")
public class PrivacyController {
    public static final String POLICY_VERSION = "2026-11-07-v1";
    private final JdbcTemplate jdbc;
    private final UserRepository users;
    @Value("${finflow.privacy.controller-name:}") private String controllerName;
    @Value("${finflow.privacy.contact-email:}") private String contactEmail;
    @Value("${finflow.privacy.contact-address:}") private String contactAddress;
    @Value("${finflow.privacy.contact-phone:}") private String contactPhone;

    public record ConsentRequest(Boolean accepted, String policyVersion, Boolean marketingConsent) {}
    public record RightsRequest(String type, String description) {}

    public boolean ready() {
        return controllerName != null && !controllerName.isBlank() &&
             contactEmail != null && contactEmail.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$") &&
             contactAddress != null && !contactAddress.isBlank() &&
             contactPhone != null && !contactPhone.isBlank();
    }

    @GetMapping("/policy")
    public Map<String,Object> policy() {
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("version",POLICY_VERSION);
        result.put("ready",ready());
        result.put("controllerName",controllerName == null ? "" : controllerName);
        result.put("contactEmail",contactEmail == null ? "" : contactEmail);
        result.put("contactAddress",contactAddress == null ? "" : contactAddress);
        result.put("contactPhone",contactPhone == null ? "" : contactPhone);
        result.put("effectiveDate","2026-11-07");
        return result;
    }

    @GetMapping("/status")
    public Map<String,Object> status(Authentication auth) {
        User user = actor(auth);
        Long count = jdbc.queryForObject("""
            SELECT COUNT(*) FROM privacy_consents
              WHERE user_id=? AND policy_version=?
            """,Long.class,user.getId(),POLICY_VERSION);
        return Map.of("accepted",count != null && count > 0,
                "version",POLICY_VERSION,"policyReady",ready());
    }

    @PostMapping("/consent")
    @Transactional
    public Map<String,Object> consent(Authentication auth, @RequestBody ConsentRequest request) {
        User user = actor(auth);
        if(!ready()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Debemos completar y publicar la política antes de recoger autorizaciones");
        if(request == null || !Boolean.TRUE.equals(request.accepted()) ||
                !POLICY_VERSION.equals(request.policyVersion()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Se requiere aceptación expresa de la política vigente");
        jdbc.update("""
            INSERT INTO privacy_consents(user_id,policy_version,marketing_opt_in,source)
            VALUES (?,?,?,'LEGACY_LOGIN')
            ON CONFLICT(user_id,policy_version) DO NOTHING
            """,user.getId(),POLICY_VERSION,Boolean.TRUE.equals(request.marketingConsent()));
        return Map.of("accepted",true,"version",POLICY_VERSION);
    }

    @PostMapping("/rights-request")
    @Transactional
    public Map<String,Object> rightsRequest(Authentication auth,@RequestBody RightsRequest request) {
        User user = actor(auth);
        if(request == null || request.type() == null ||
                !Set.of("ACCESS","RECTIFY","DELETE","REVOKE").contains(request.type()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Selecciona un tipo de solicitud válido");
        String details = request.description() == null ? "" : request.description().trim();
        if(details.length() > 500) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"El detalle es demasiado largo");
        jdbc.update("""
            INSERT INTO privacy_rights_requests(user_id,request_type,description)
            VALUES (?,?,?)
            """,user.getId(),request.type(),details);
        return Map.of("status","PENDING","message","Solicitud recibida para atención por el responsable");
    }

    private User actor(Authentication auth) {
        if(auth == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Inicia sesión");
        return users.findByEmail(auth.getName()).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Sesión inválida"));
    }
}
