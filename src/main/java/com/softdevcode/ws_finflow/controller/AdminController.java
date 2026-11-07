package com.softdevcode.ws_finflow.controller;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/admin")
public class AdminController {
    // SecurityConfig exige ROLE_ADMIN, extraído del rol real en la base de datos.
    @GetMapping("/verify")
    public Map<String,String> verify(Authentication auth) {
        return Map.of("status", "ok", "role", "ADMIN");
    }
}
