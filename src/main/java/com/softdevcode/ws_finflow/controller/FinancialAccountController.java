package com.softdevcode.ws_finflow.controller;

import com.softdevcode.ws_finflow.entity.User;
import com.softdevcode.ws_finflow.repository.UserRepository;
import com.softdevcode.ws_finflow.service.FinancialAccountService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.List;

@RestController
@RequestMapping("/accounts")
@RequiredArgsConstructor
public class FinancialAccountController {
    private final FinancialAccountService service;
    private final UserRepository users;

    private Integer owner(Authentication auth) {
        if(auth==null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        return Math.toIntExact(users.findByEmail(auth.getName()).map(User::getId)
            .orElseThrow(()->new ResponseStatusException(HttpStatus.UNAUTHORIZED)));
    }

    @GetMapping
    public List<FinancialAccountService.AccountView> list(Authentication auth) {
        return service.list(owner(auth));
    }

    @PostMapping
    public ResponseEntity<FinancialAccountService.AccountView> create(Authentication auth,
            @RequestBody FinancialAccountService.AccountRequest body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(owner(auth),body));
    }

    @PutMapping("/{id}")
    public FinancialAccountService.AccountView update(Authentication auth,@PathVariable Long id,
            @RequestBody FinancialAccountService.AccountUpdate body) {
        return service.update(owner(auth),id,body);
    }

    @PatchMapping("/{id}/archive")
    public FinancialAccountService.AccountView archive(Authentication auth,@PathVariable Long id,
            @RequestBody FinancialAccountService.ArchiveRequest body) {
        return service.archive(owner(auth),id,body);
    }

    @GetMapping("/transfers")
    public List<FinancialAccountService.TransferView> transfers(Authentication auth) {
        return service.transfers(owner(auth));
    }

    @PostMapping("/transfers")
    public ResponseEntity<FinancialAccountService.TransferView> transfer(Authentication auth,
            @RequestBody FinancialAccountService.TransferRequest body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.transfer(owner(auth),body));
    }
}
