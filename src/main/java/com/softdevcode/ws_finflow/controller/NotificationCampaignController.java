package com.softdevcode.ws_finflow.controller;

import com.softdevcode.ws_finflow.repository.UserRepository;
import com.softdevcode.ws_finflow.service.NotificationCampaignService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.Map;
import java.util.UUID;

@RestController
public class NotificationCampaignController {
    private final NotificationCampaignService service;
    private final UserRepository users;
    public NotificationCampaignController(NotificationCampaignService service, UserRepository users) {
        this.service = service; this.users = users;
    }
    @GetMapping("/admin/notifications")
    public Object list() { return Map.of("capabilities", service.capabilities(), "campaigns", service.list()); }
    @PostMapping("/admin/notifications")
    @ResponseStatus(HttpStatus.CREATED)
    public Object schedule(Authentication auth, @RequestBody NotificationCampaignService.Schedule request) {
        return service.create(owner(auth), request);
    }
    @DeleteMapping("/admin/notifications/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancel(@PathVariable UUID id) { service.cancel(id); }
    @PutMapping("/notifications/device")
    public Object device(Authentication auth, @RequestBody NotificationCampaignService.Device request) {
        service.device(owner(auth), request); return Map.of("enabled", request.enabled());
    }
    @GetMapping("/notifications/messages")
    public Object feed(Authentication auth, @RequestParam String deviceId) { return service.feed(owner(auth), deviceId); }
    @PostMapping("/notifications/messages/{id}/ack")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void acknowledge(Authentication auth, @PathVariable UUID id, @RequestParam String deviceId) {
        service.acknowledge(owner(auth), deviceId, id);
    }
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<?> error(ResponseStatusException ex) {
        return ResponseEntity.status(ex.getStatusCode()).body(Map.of("error", ex.getReason() == null ? "Solicitud inválida." : ex.getReason()));
    }
    private long owner(Authentication auth) {
        if (auth == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        return users.findByEmail(auth.getName()).orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED)).getId();
    }
}
