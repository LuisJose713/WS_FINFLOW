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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Value;
import java.time.Instant;
import java.time.ZoneOffset;
import java.sql.Timestamp;

import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/auth")
public class AuthController {
    @Autowired private UserRepository userRepository;
    @Autowired private JwtUtil jwtUtil;
    @Autowired private JdbcTemplate jdbc;
    @Value("${finflow.privacy.controller-name:}") private String privacyControllerName;
    @Value("${finflow.privacy.contact-email:}") private String privacyContactEmail;
    @Value("${finflow.privacy.contact-address:}") private String privacyContactAddress;
    @Value("${finflow.privacy.contact-phone:}") private String privacyContactPhone;
    private static final String PRIVACY_VERSION = "2026-11-07-v1";
    @Autowired private com.softdevcode.ws_finflow.security.AuthRateLimiter rateLimiter;
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
                "token", token,
                "user", Map.of("id", user.getId(), "name", user.getUsername(), "email", user.getEmail(), "role", user.getRole())));
    }

    @PostMapping("/register")
    @Transactional
    public ResponseEntity<?> register(@RequestBody RegisterRequest request,
                                      @RequestHeader(value = "X-Client-Type", defaultValue = "web") String clientType) {
        if (request == null || request.getName() == null || request.getEmail() == null || request.getPassword() == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Solicitud inválida"));
        }
        if (privacyControllerName == null || privacyControllerName.isBlank()
             || privacyContactEmail == null || privacyContactEmail.isBlank()
             || privacyContactAddress == null || privacyContactAddress.isBlank()
             || privacyContactPhone == null || privacyContactPhone.isBlank()) {
            return ResponseEntity.status(503).body(Map.of("error","Política de privacidad pendiente de publicación"));
        }
        if (!Boolean.TRUE.equals(request.getPrivacyAccepted()) ||
                !PRIVACY_VERSION.equals(request.getPrivacyVersion())) {
            return ResponseEntity.badRequest().body(Map.of("error","Debes aceptar la política de tratamiento de datos vigente"));
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
        jdbc.update("""
            INSERT INTO privacy_consents(user_id,policy_version,marketing_opt_in,source)
            VALUES (?,?,?,'SIGNUP')
            """,user.getId(),PRIVACY_VERSION,Boolean.TRUE.equals(request.getMarketingConsent()));
        Instant started = Instant.now();
        jdbc.update("""
            INSERT INTO billing_trials(user_id,started_at,expires_at)
            VALUES (?,?,?) ON CONFLICT (user_id) DO NOTHING
            """,user.getId(),Timestamp.from(started),
                Timestamp.from(started.atZone(ZoneOffset.UTC).plusMonths(1).toInstant()));
        String token = jwtUtil.generateToken(user.getEmail(), false, user.getTokenVersion());
        return ResponseEntity.ok(Map.of(
                "token", token,
                "user", Map.of("id", user.getId(), "name", user.getUsername(), "email", user.getEmail(), "role", user.getRole())));
    }

    public record ChangePasswordRequest(String currentPassword,String newPassword,String confirmPassword){}
    public record ProfileUpdateRequest(String name,String email,String currentPassword){}

    @PutMapping("/profile")
    @Transactional
    public ResponseEntity<?> updateProfile(Authentication authentication,@RequestBody ProfileUpdateRequest request) {
        if (authentication == null) return ResponseEntity.status(401).body(Map.of("error","Sesión requerida"));
        User user = userRepository.findByEmail(authentication.getName())
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.UNAUTHORIZED));
        if (request == null || request.name() == null || request.email() == null ||
                request.currentPassword() == null ||
                !passwordEncoder.matches(request.currentPassword(),user.getPassword()))
            return ResponseEntity.status(403).body(Map.of("error","Verifica tu contraseña actual"));
        String name = request.name().trim();
        String email = request.email().trim().toLowerCase(java.util.Locale.ROOT);
        if (name.length() < 2 || name.length() > 80 ||
                email.length() > 254 ||
                !email.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$"))
            return ResponseEntity.badRequest().body(Map.of("error","Nombre o correo inválido"));
        if (!email.equalsIgnoreCase(user.getEmail()) &&
                userRepository.findByEmail(email).filter(other -> !other.getId().equals(user.getId())).isPresent())
            return ResponseEntity.status(409).body(Map.of("error","El correo ya está registrado"));
        if (!name.equals(user.getUsername()) &&
                userRepository.findByUsername(name).filter(other -> !other.getId().equals(user.getId())).isPresent())
            return ResponseEntity.status(409).body(Map.of("error","El nombre ya está registrado"));
        boolean emailChanged = !email.equalsIgnoreCase(user.getEmail());
        user.setUsername(name);
        user.setEmail(email);
        // Invalidate all previous sessions after any profile update.
        user.setTokenVersion((user.getTokenVersion()==null ? 0 : user.getTokenVersion()) + 1);
        userRepository.save(user);
        return ResponseEntity.ok(Map.of("status","updated","logoutRequired",true,
                "emailChanged",emailChanged,"name",name,"email",email));
    }
    public record ConfirmPasswordRequest(String password){}

    @PostMapping("/change-password")
    public ResponseEntity<?> changePassword(Authentication authentication,@RequestBody ChangePasswordRequest request){
        if(authentication==null)
            return ResponseEntity.status(401).body(Map.of("error","Sesión requerida"));
        User user=userRepository.findByEmail(authentication.getName())
            .orElseThrow(()->new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.UNAUTHORIZED));
        if(request==null||request.currentPassword()==null||request.newPassword()==null||
                request.confirmPassword()==null || !request.newPassword().equals(request.confirmPassword()) ||
                request.newPassword().length()<12||request.newPassword().length()>128)
            return ResponseEntity.badRequest().body(Map.of("error","La nueva contraseña debe tener al menos 12 caracteres y coincidir"));
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
}
