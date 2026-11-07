package com.softdevcode.ws_finflow.controller;

import com.softdevcode.ws_finflow.dto.ContributionRequest;
import com.softdevcode.ws_finflow.dto.GoalRequest;
import com.softdevcode.ws_finflow.entity.Goal;
import com.softdevcode.ws_finflow.entity.GoalContribution;
import com.softdevcode.ws_finflow.service.GoalService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import com.softdevcode.ws_finflow.entity.User;
import com.softdevcode.ws_finflow.repository.UserRepository;
import org.springframework.security.core.Authentication;
import java.util.Map;

@RestController
@RequestMapping("/goals")
public class GoalController {

    @Autowired
    private GoalService service;

    @Autowired
    private UserRepository userRepository;

    private Long authenticatedUserId(Authentication authentication) {
        return userRepository.findByEmail(authentication.getName())
                .map(User::getId)
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.UNAUTHORIZED, "Usuario no encontrado"));
    }

    // GET /goals
    @GetMapping
    public ResponseEntity<List<Goal>> getAll(Authentication authentication) {
        return ResponseEntity.ok(service.getAll(authenticatedUserId(authentication)));
    }

    // GET /goals/{id}
    @GetMapping("/{id}")
    public ResponseEntity<Goal> getById(Authentication authentication, @PathVariable Long id) {
        return ResponseEntity.ok(service.getById(authenticatedUserId(authentication), id));
    }

    // POST /goals
    @PostMapping
    public ResponseEntity<Goal> create(Authentication authentication, @RequestBody GoalRequest request) {
        return ResponseEntity.ok(service.create(authenticatedUserId(authentication), request));
    }

    // PUT /goals/{id}
    @PutMapping("/{id}")
    public ResponseEntity<Goal> update(Authentication authentication, @PathVariable Long id, @RequestBody GoalRequest request) {
        return ResponseEntity.ok(service.update(authenticatedUserId(authentication), id, request));
    }

    // PATCH /goals/{id}/contribute
    @PatchMapping("/{id}/contribute")
    public ResponseEntity<Goal> addContribution(Authentication authentication, @PathVariable Long id, @RequestBody ContributionRequest request) {
        return ResponseEntity.ok(service.addContribution(authenticatedUserId(authentication), id, request));
    }

    // GET /goals/{id}/contributions — historial de abonos
    @GetMapping("/{id}/contributions")
    public ResponseEntity<List<GoalContribution>> getContributions(Authentication authentication, @PathVariable Long id) {
        return ResponseEntity.ok(service.getContributions(authenticatedUserId(authentication), id));
    }

    // PATCH /goals/{id}/status
    @PatchMapping("/{id}/status")
    public ResponseEntity<Goal> updateStatus(Authentication authentication, @PathVariable Long id, @RequestBody Map<String, String> body) {
        return ResponseEntity.ok(service.updateStatus(authenticatedUserId(authentication), id, body.get("status")));
    }

    // DELETE /goals/{id}
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(Authentication authentication, @PathVariable Long id) {
        service.delete(authenticatedUserId(authentication), id);
        return ResponseEntity.noContent().build();
    }
}

