package com.softdevcode.ws_finflow.controller;

import com.softdevcode.ws_finflow.dto.BudgetRequest;
import com.softdevcode.ws_finflow.dto.BudgetLimitRequest;
import com.softdevcode.ws_finflow.dto.BudgetOverview;
import com.softdevcode.ws_finflow.entity.MonthlyBudget;
import com.softdevcode.ws_finflow.entity.User;
import com.softdevcode.ws_finflow.repository.UserRepository;
import com.softdevcode.ws_finflow.service.MonthlyBudgetService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.List;

@RestController
@RequestMapping("/budgets")
@RequiredArgsConstructor
public class MonthlyBudgetController {
    private final MonthlyBudgetService service;
    private final UserRepository users;

    private Integer owner(Authentication authentication) {
        if (authentication == null)
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        return Math.toIntExact(users.findByEmail(authentication.getName()).map(User::getId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED)));
    }

    @GetMapping
    public List<BudgetOverview> list(Authentication auth,
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) Integer month) {
        return service.list(owner(auth), year, month);
    }

    @PostMapping
    public ResponseEntity<MonthlyBudget> create(Authentication auth, @RequestBody BudgetRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(owner(auth), request));
    }

    @PutMapping("/{id}")
    public MonthlyBudget changeLimit(Authentication auth, @PathVariable Long id,
            @RequestBody BudgetLimitRequest request) {
        return service.changeLimit(owner(auth), id, request);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(Authentication auth, @PathVariable Long id) {
        service.delete(owner(auth), id);
        return ResponseEntity.noContent().build();
    }
}