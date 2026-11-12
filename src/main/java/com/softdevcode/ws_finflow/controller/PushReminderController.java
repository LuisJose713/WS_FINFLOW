package com.softdevcode.ws_finflow.controller;

import com.softdevcode.ws_finflow.service.PushReminderService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.Map;

@RestController
@RequestMapping("/notifications/push")
public class PushReminderController {
    private final PushReminderService service;
    public PushReminderController(PushReminderService service) { this.service = service; }

    public record Subscription(String endpoint, Keys keys) {
        public record Keys(String p256dh, String auth) {}
    }

    @GetMapping("/config")
    public Map<String, Object> config(Authentication auth) {
        owner(auth);
        return Map.of("enabled", service.configured(), "publicKey", service.publicKey());
    }

    @PostMapping("/subscriptions")
    public Map<String, Boolean> subscribe(Authentication auth, @RequestBody Subscription subscription) {
        long userId = owner(auth);
        if (subscription == null || subscription.keys() == null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        service.subscribe(userId, subscription.endpoint(), subscription.keys().p256dh(), subscription.keys().auth());
        return Map.of("subscribed", true);
    }

    @DeleteMapping("/subscriptions")
    public void unsubscribe(Authentication auth, @RequestBody Map<String, String> body) {
        service.unsubscribe(owner(auth), body.get("endpoint"));
    }

    @PostMapping("/test")
    public Map<String, Integer> test(Authentication auth) {
        owner(auth);
        return Map.of("acceptedByPushServices", service.send(true));
    }

    private long owner(Authentication auth) {
        if (auth == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        return service.ownerId(auth.getName());
    }
}
