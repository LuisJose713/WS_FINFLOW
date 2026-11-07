package com.softdevcode.ws_finflow.controller;

import com.softdevcode.ws_finflow.dto.LoginRequest;
import com.softdevcode.ws_finflow.dto.RegisterRequest;
import com.softdevcode.ws_finflow.entity.User;
import com.softdevcode.ws_finflow.repository.UserRepository;
import com.softdevcode.ws_finflow.security.JwtUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import org.springframework.security.core.Authentication;

@RestController
@RequestMapping("/auth")
public class AuthController {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JwtUtil jwtUtil;

    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    // ── Login ─────────────────────────────────────────────
    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest request,
                                   @RequestHeader(value = "X-Client-Type", defaultValue = "web") String clientType){
    {



            Optional<User> userOpt = userRepository.findByEmail(request.getEmail());

            if (userOpt.isEmpty()) {
                return ResponseEntity.status(401).body(Map.of("error", "Credenciales inválidas"));
            }

            User user = userOpt.get();

            // Verifica contraseña encriptada
            if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
                return ResponseEntity.status(401).body(Map.of("error", "Credenciales inválidas"));
            }

            boolean isMobile = "mobile".equalsIgnoreCase(clientType);
            String token = jwtUtil.generateToken(user.getEmail(), isMobile);

            return ResponseEntity.ok(Map.of(
                    "token", token,
                    "user", Map.of(
                            "id",    user.getId(),
                            "name",  user.getUsername(),
                            "email", user.getEmail()
                    )
            ));
        }
    }

    // ── Register ──────────────────────────────────────────
    @PostMapping("/register")
    public ResponseEntity<?> register(@RequestBody RegisterRequest request, @RequestHeader(value = "X-Client-Type", defaultValue = "web") String clientType) {


        // Verifica si el email ya existe
        if (userRepository.findByEmail(request.getEmail()).isPresent()) {
            return ResponseEntity.status(409).body(Map.of("error", "El email ya está registrado"));
        }

        // Verifica si el username ya existe
        if (userRepository.findByUsername(request.getName()).isPresent()) {
            return ResponseEntity.status(409).body(Map.of("error", "El nombre de usuario ya está en uso"));
        }

        // Encripta la contraseña
        String hashedPassword = passwordEncoder.encode(request.getPassword());

        User user = new User();
        user.setUsername(request.getName());
        user.setEmail(request.getEmail());
        user.setPassword(hashedPassword);

        userRepository.save(user);

        // Genera token directamente — el usuario queda logueado al registrarse
        boolean isMobile = "mobile".equalsIgnoreCase(clientType); // ← definido aquí
        String token = jwtUtil.generateToken(user.getEmail(), isMobile);

        return ResponseEntity.ok(Map.of(
                "token", token,
                "user", Map.of(
                        "id",    user.getId(),
                        "name",  user.getUsername(),
                        "email", user.getEmail()
                )
        ));
    }

    @GetMapping("/me")
    public ResponseEntity<?> me(Authentication authentication) {
        String email = authentication.getName();
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("Usuario no encontrado"));

        return ResponseEntity.ok(Map.of(
                "id",    user.getId(),
                "name",  user.getUsername(),
                "email", user.getEmail()
        ));
    }
}