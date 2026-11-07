package com.softdevcode.ws_finflow.controller;

import com.softdevcode.ws_finflow.entity.User;
import com.softdevcode.ws_finflow.repository.UserRepository;
import com.softdevcode.ws_finflow.service.RecurringRuleService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/recurring")
@RequiredArgsConstructor
public class RecurringRuleController {
    private final RecurringRuleService service;
    private final UserRepository users;
    private Integer owner(Authentication auth) {
        if(auth==null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        return Math.toIntExact(users.findByEmail(auth.getName()).map(User::getId)
            .orElseThrow(()->new ResponseStatusException(HttpStatus.UNAUTHORIZED)));
    }

    @GetMapping
    public List<RecurringRuleService.Rule> list(Authentication auth){return service.list(owner(auth));}

    @PostMapping
    public ResponseEntity<RecurringRuleService.Rule> create(Authentication auth,
           @RequestBody RecurringRuleService.RuleRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(owner(auth),request));
    }

    @PatchMapping("/{id}/active")
    public RecurringRuleService.Rule activate(Authentication auth,@PathVariable Long id,
        @RequestBody RecurringRuleService.ActivationRequest body) {
        return service.activate(owner(auth),id,body);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(Authentication auth,@PathVariable Long id){
        service.delete(owner(auth),id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/process-due")
    public Map<String,Integer> process(Authentication auth) {
        return Map.of("created",service.processDue(owner(auth)));
    }
}
