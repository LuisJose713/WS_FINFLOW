package com.softdevcode.ws_finflow.controller;

import com.softdevcode.ws_finflow.dto.PlannedExpenseRequest;
import com.softdevcode.ws_finflow.entity.PlannedExpense;
import com.softdevcode.ws_finflow.service.PlannedExpenseService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import com.softdevcode.ws_finflow.entity.User;
import com.softdevcode.ws_finflow.repository.UserRepository;
import org.springframework.security.core.Authentication;

@RestController
@RequestMapping("/planned-expenses")
@CrossOrigin(origins = "*")
public class PlannedExpenseController {

    @Autowired
    private PlannedExpenseService service;

    @Autowired
    private UserRepository userRepository;

    private Integer authenticatedUserId(Authentication authentication) {
        Long id = userRepository.findByEmail(authentication.getName())
                .map(User::getId)
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.UNAUTHORIZED, "Usuario no encontrado"));
        return Math.toIntExact(id);
    }

    // GET /planned-expenses
    @GetMapping
    public ResponseEntity<List<PlannedExpense>> getAll(
            Authentication authentication) {
        return ResponseEntity.ok(service.getAll(authenticatedUserId(authentication)));
    }

    // POST /planned-expenses
    @PostMapping
    public ResponseEntity<PlannedExpense> create(
            Authentication authentication,
            @RequestBody PlannedExpenseRequest req) {
        return ResponseEntity.ok(service.create(authenticatedUserId(authentication), req));
    }

    // PUT /planned-expenses/{id}
    @PutMapping("/{id}")
    public ResponseEntity<PlannedExpense> update(
            Authentication authentication,
            @PathVariable Long id,
            @RequestBody PlannedExpenseRequest req) {
        return ResponseEntity.ok(service.update(authenticatedUserId(authentication), id, req));
    }

    // PATCH /planned-expenses/{id}/execute — marca como hecho y crea transacción
    @PatchMapping("/{id}/execute")
    public ResponseEntity<PlannedExpense> execute(
            Authentication authentication,
            @PathVariable Long id) {
        return ResponseEntity.ok(service.execute(authenticatedUserId(authentication), id));
    }

    // PATCH /planned-expenses/{id}/cancel
    @PatchMapping("/{id}/cancel")
    public ResponseEntity<PlannedExpense> cancel(
            Authentication authentication,
            @PathVariable Long id) {
        return ResponseEntity.ok(service.cancel(authenticatedUserId(authentication), id));
    }

    // DELETE /planned-expenses/{id}
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(
            Authentication authentication,
            @PathVariable Long id) {
        service.delete(authenticatedUserId(authentication), id);
        return ResponseEntity.noContent().build();
    }

    // GET /planned-expenses/overdue — gastos vencidos
    @GetMapping("/overdue")
    public ResponseEntity<List<PlannedExpense>> getOverdue(
            Authentication authentication) {
        return ResponseEntity.ok(service.getOverdue(authenticatedUserId(authentication)));
    }

    // GET /planned-expenses/reminders — recordatorios de hoy
    @GetMapping("/reminders")
    public ResponseEntity<List<PlannedExpense>> getReminders(
            Authentication authentication) {
        return ResponseEntity.ok(service.getTodayReminders(authenticatedUserId(authentication)));
    }
}