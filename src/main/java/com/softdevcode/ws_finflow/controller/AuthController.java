package com.softdevcode.ws_finflow.controller;

import com.softdevcode.ws_finflow.dto.LoginRequest;
import com.softdevcode.ws_finflow.config.PrivacyPolicySettings;
import com.softdevcode.ws_finflow.dto.RegisterRequest;
import com.softdevcode.ws_finflow.entity.User;
import com.softdevcode.ws_finflow.repository.UserRepository;
import com.softdevcode.ws_finflow.security.JwtUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.time.ZoneOffset;
import java.sql.Timestamp;
import java.nio.charset.StandardCharsets;

import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/auth")
public class AuthController {
    @Autowired private UserRepository userRepository;
    @Autowired private JwtUtil jwtUtil;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PrivacyPolicySettings privacySettings;
    @Autowired private com.softdevcode.ws_finflow.security.AuthRateLimiter rateLimiter;
    @Autowired private com.softdevcode.ws_finflow.security.RefreshTokenService refreshTokens;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest request,
                                   @RequestHeader(value = "X-Client-Type", defaultValue = "web") String clientType) {
        if (request == null || request.getEmail() == null || request.getEmail().isBlank()
                || request.getEmail().length() > 254
                || request.getPassword() == null || request.getPassword().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Solicitud inválida"));
        }
        if (exceedsBcryptLimit(request.getPassword())) {
            return passwordTooLong();
        }
        String normalizedEmail=request.getEmail().trim();
        if(rateLimiter.isLocked(normalizedEmail)) {
            return ResponseEntity.status(429).body(Map.of("error","Demasiados intentos. Prueba más tarde."));
        }
        Optional<User> userOpt = userRepository.findByEmail(normalizedEmail);
        if (userOpt.isEmpty() || !passwordEncoder.matches(request.getPassword(), userOpt.get().getPassword())) {
            // Do not disclose whether the account exists. Throttle by a hashed identifier.
            rateLimiter.recordFailure(normalizedEmail);
            return ResponseEntity.status(401).body(Map.of("error", "Credenciales inválidas"));
        }
        User user = userOpt.get();
        rateLimiter.clear(normalizedEmail);
        // Mobile TTL must not be granted based on an untrusted client-supplied header.
        String token = jwtUtil.generateToken(user.getEmail(), false, user.getTokenVersion());
        return ResponseEntity.ok(Map.of(
                "token", token, "refreshToken", refreshTokens.issue(user),
                "user", Map.of("id", user.getId(), "name", user.getUsername(), "email", user.getEmail(), "role", user.getRole())));
    }

    @PostMapping("/register")
    @Transactional
    public ResponseEntity<?> register(@RequestBody RegisterRequest request,
                                      @RequestHeader(value = "X-Client-Type", defaultValue = "web") String clientType) {
        if (request == null || request.getName() == null || request.getEmail() == null || request.getPassword() == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Solicitud inválida"));
        }
        String name = request.getName().trim();
        String email = request.getEmail().trim();
        String password = request.getPassword();
        if (exceedsBcryptLimit(password)) return passwordTooLong();
        if (name.length() < 2 || name.length() > 80 || email.length() > 254
                || !email.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")
                || password.length() < 8) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Verifica nombre, correo y contraseña (mínimo 8 caracteres)"));
        }
        if (request.getConfirmPassword() != null && !password.equals(request.getConfirmPassword())) {
            return ResponseEntity.badRequest().body(Map.of("error", "Las contraseñas no coinciden"));
        }
        // The legal policy must be independently approved before we enforce
        // versioned consent. During rollout, existing registration stays available.
        // Once published, refusal or absent consent blocks registration.
        if (privacySettings.ready() &&
                (!Boolean.TRUE.equals(request.getPrivacyAccepted()) ||
                 !PrivacyPolicySettings.VERSION.equals(request.getPrivacyVersion()))) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Debes autorizar expresamente la política de tratamiento vigente"));
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
        if (privacySettings.ready()) {
            boolean marketing = Boolean.TRUE.equals(request.getMarketingConsent());
            jdbc.update("""
                    INSERT INTO privacy_consents(user_id,policy_version,marketing_opt_in,source)
                    VALUES (?,?,?,'SIGNUP')
                    """, user.getId(), PrivacyPolicySettings.VERSION, marketing);
            jdbc.update("""
                    INSERT INTO privacy_marketing_events(user_id,policy_version,marketing_opt_in,source)
                    VALUES (?,?,?,'SIGNUP')
                    """, user.getId(), PrivacyPolicySettings.VERSION, marketing);
        }
        // Grant one free month of Pro on first registration; no payment or auto-renewal.
        Instant trialStart = Instant.now();
        jdbc.update("""
            INSERT INTO billing_trials(user_id,started_at,expires_at)
            VALUES (?,?,?) ON CONFLICT (user_id) DO NOTHING
            """, user.getId(), Timestamp.from(trialStart),
                Timestamp.from(trialStart.atZone(ZoneOffset.UTC).plusMonths(1).toInstant()));
        String token = jwtUtil.generateToken(user.getEmail(), false, user.getTokenVersion());
        return ResponseEntity.ok(Map.of(
                "token", token, "refreshToken", refreshTokens.issue(user),
                "user", Map.of("id", user.getId(), "name", user.getUsername(), "email", user.getEmail(), "role", user.getRole())));
    }

    public record ChangePasswordRequest(String currentPassword,String newPassword,String confirmPassword){}
    public record ConfirmPasswordRequest(String password){}
    public record RefreshRequest(String refreshToken){}

    @PostMapping("/session")
    public ResponseEntity<?> adoptSession(Authentication authentication) {
        if (authentication == null) return ResponseEntity.status(401).build();
        User user = userRepository.findByEmail(authentication.getName()).orElseThrow(() ->
            new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.UNAUTHORIZED));
        int version = user.getTokenVersion() == null ? 0 : user.getTokenVersion();
        return ResponseEntity.ok(Map.of("token", jwtUtil.generateToken(user.getEmail(), false, version),
            "refreshToken", refreshTokens.issue(user), "user", Map.of("id", user.getId(),
                "name", user.getUsername(), "email", user.getEmail(), "role", user.getRole())));
    }

    @PostMapping("/refresh")
    public ResponseEntity<?> refresh(@RequestBody RefreshRequest request) {
        return ResponseEntity.ok(refreshTokens.refresh(request == null ? null : request.refreshToken()));
    }

    @PostMapping("/logout")
    public ResponseEntity<?> logout(@RequestBody(required = false) RefreshRequest request) {
        refreshTokens.logout(request == null ? null : request.refreshToken());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/change-password")
    public ResponseEntity<?> changePassword(Authentication authentication,@RequestBody ChangePasswordRequest request){
        if(authentication==null)
            return ResponseEntity.status(401).body(Map.of("error","Sesión requerida"));
        User user=userRepository.findByEmail(authentication.getName())
            .orElseThrow(()->new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.UNAUTHORIZED));
        if(request==null||request.currentPassword()==null||request.newPassword()==null||
                request.confirmPassword()==null || !request.newPassword().equals(request.confirmPassword()) ||
                request.newPassword().length()<12)
            return ResponseEntity.badRequest().body(Map.of("error","La nueva contraseña debe tener al menos 12 caracteres y coincidir"));
        if (exceedsBcryptLimit(request.currentPassword()) || exceedsBcryptLimit(request.newPassword()))
            return passwordTooLong();
        if(!passwordEncoder.matches(request.currentPassword(),user.getPassword()))
            return ResponseEntity.status(403).body(Map.of("error","Contraseña actual incorrecta"));
        if(passwordEncoder.matches(request.newPassword(),user.getPassword()))
            return ResponseEntity.badRequest().body(Map.of("error","La nueva contraseña debe ser diferente"));
        user.setPassword(passwordEncoder.encode(request.newPassword()));
        user.setTokenVersion((user.getTokenVersion()==null?0:user.getTokenVersion())+1);
        user.setPasswordChangedAt(java.time.LocalDateTime.now());
        userRepository.save(user);
        return ResponseEntity.ok(Map.of("status","password_changed","logoutRequired",true));
    }

    @PostMapping("/revoke-sessions")
    public ResponseEntity<?> revokeSessions(Authentication authentication,@RequestBody ConfirmPasswordRequest request){
        if(authentication==null) return ResponseEntity.status(401).body(Map.of("error","Sesión requerida"));
        User user=userRepository.findByEmail(authentication.getName())
            .orElseThrow(()->new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.UNAUTHORIZED));
        if (request != null && request.password() != null && exceedsBcryptLimit(request.password()))
            return passwordTooLong();
        if(request==null||request.password()==null||
            !passwordEncoder.matches(request.password(),user.getPassword()))
            return ResponseEntity.status(403).body(Map.of("error","Contraseña incorrecta"));
        user.setTokenVersion((user.getTokenVersion()==null?0:user.getTokenVersion())+1);
        userRepository.save(user);
        return ResponseEntity.ok(Map.of("status","sessions_revoked","logoutRequired",true));
    }

    @GetMapping("/me")
    public ResponseEntity<?> me(Authentication authentication) {
        User user = userRepository.findByEmail(authentication.getName())
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.UNAUTHORIZED, "Usuario no encontrado"));
        return ResponseEntity.ok(Map.of(
                "id", user.getId(), "name", user.getUsername(), "email", user.getEmail(), "role", user.getRole()));
    }

    // BCrypt's input limit is bytes, not Java characters. Never truncate passwords:
    // different suffixes must not be accepted as the same credential.
    private boolean exceedsBcryptLimit(String password) {
        return password.length() > 72 || password.getBytes(StandardCharsets.UTF_8).length > 72;
    }

    private ResponseEntity<?> passwordTooLong() {
        return ResponseEntity.badRequest().body(Map.of("error",
                "La contraseña supera el máximo de 72 bytes UTF-8; algunos caracteres ocupan varios bytes."));
    }
}
