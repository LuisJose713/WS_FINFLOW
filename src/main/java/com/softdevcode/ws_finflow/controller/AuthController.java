package com.softdevcode.ws_finflow.controller;

import com.softdevcode.ws_finflow.dto.LoginRequest;
import com.softdevcode.ws_finflow.dto.RegisterRequest;
import com.softdevcode.ws_finflow.entity.User;
import com.softdevcode.ws_finflow.repository.UserRepository;
import com.softdevcode.ws_finflow.security.JwtUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/auth")
public class AuthController {
    @Autowired private UserRepository userRepository;
    @Autowired private JwtUtil jwtUtil;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest request,
                                   @RequestHeader(value = "X-Client-Type", defaultValue = "web") String clientType) {
        if (request == null || request.getEmail() == null || request.getEmail().isBlank()
                || request.getEmail().length() > 254
                || request.getPassword() == null || request.getPassword().isBlank()
                || request.getPassword().length() > 128) {
            return ResponseEntity.badRequest().body(Map.of("error", "Solicitud inválida"));
        }
        Optional<User> userOpt = userRepository.findByEmail(request.getEmail().trim());
        if (userOpt.isEmpty() || !passwordEncoder.matches(request.getPassword(), userOpt.get().getPassword())) {
            // Never reveal whether an account exists.
            return ResponseEntity.status(401).body(Map.of("error", "Credenciales inválidas"));
        }
        User user = userOpt.get();
        String token = jwtUtil.generateToken(user.getEmail(), "mobile".equalsIgnoreCase(clientType));
        return ResponseEntity.ok(Map.of(
                "token", token,
                "user", Map.of("id", user.getId(), "name", user.getUsername(), "email", user.getEmail(), "role", user.getRole())));
    }

    @PostMapping("/register")
    public ResponseEntity<?> register(@RequestBody RegisterRequest request,
                                      @RequestHeader(value = "X-Client-Type", defaultValue = "web") String clientType) {
        if (request == null || request.getName() == null || request.getEmail() == null || request.getPassword() == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Solicitud inválida"));
        }
        String name = request.getName().trim();
        String email = request.getEmail().trim();
        String password = request.getPassword();
        if (name.length() < 2 || name.length() > 80 || email.length() > 254
                || !email.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")
                || password.length() < 8 || password.length() > 128) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Verifica nombre, correo y contraseña (mínimo 8 caracteres)"));
        }
        if (request.getConfirmPassword() != null && !password.equals(request.getConfirmPassword())) {
            return ResponseEntity.badRequest().body(Map.of("error", "Las contraseñas no coinciden"));
        }
        if (userRepository.findByEmail(email).isPresent()) {
            return ResponseEntity.status(409).body(Map.of("error", "El email ya está registrado"));
        }
        if (userRepository.findByUsername(name).isPresent()) {
            return ResponseEntity.status(409).body(Map.of("error", "El nombre de usuario ya está en uso"));
        }
        User user = new User();
        user.setUsername(name);
        user.setEmail(email);
        user.setPassword(passwordEncoder.encode(password));
        // El registro público nunca puede conceder permisos administrativos.
        user.setRole("USER");
        userRepository.save(user);
        String token = jwtUtil.generateToken(user.getEmail(), "mobile".equalsIgnoreCase(clientType));
        return ResponseEntity.ok(Map.of(
                "token", token,
                "user", Map.of("id", user.getId(), "name", user.getUsername(), "email", user.getEmail())));
    }

    @GetMapping("/me")
    public ResponseEntity<?> me(Authentication authentication) {
        User user = userRepository.findByEmail(authentication.getName())
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.UNAUTHORIZED, "Usuario no encontrado"));
        return ResponseEntity.ok(Map.of(
                "id", user.getId(), "name", user.getUsername(), "email", user.getEmail(), "role", user.getRole()));
    }
}
