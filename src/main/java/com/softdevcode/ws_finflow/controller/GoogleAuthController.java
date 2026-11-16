package com.softdevcode.ws_finflow.controller;

import com.softdevcode.ws_finflow.entity.User;
import com.softdevcode.ws_finflow.config.PrivacyPolicySettings;
import com.softdevcode.ws_finflow.repository.UserRepository;
import com.softdevcode.ws_finflow.security.GoogleIdTokenVerifier;
import com.softdevcode.ws_finflow.security.JwtUtil;
import com.softdevcode.ws_finflow.security.RefreshTokenService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.time.Instant;
import java.time.ZoneOffset;
import java.sql.Timestamp;
import java.nio.charset.StandardCharsets;

/**
 * New owners can explicitly register with a verified Google identity.
 * Existing password accounts require explicit linking by the signed-in owner.
 * Never auto-link by Google email, even when email_verified=true.
 * Existing accounts retain their original user IDs, transactions and passwords.
 */
@RestController
@RequestMapping("/auth/google")
public class GoogleAuthController {
    private final GoogleIdTokenVerifier verifier;
    private final UserRepository users;
    private final JdbcTemplate jdbc;
    private final JwtUtil jwt;
    private final RefreshTokenService refreshTokens;
    private final com.softdevcode.ws_finflow.security.FieldCipher cipher;
    private final PrivacyPolicySettings privacySettings;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    public record GoogleCredential(String idToken) {}
    public record GoogleRegistration(String idToken, Boolean privacyAccepted, String privacyVersion,
                                     Boolean marketingConsent) {}
    public record GooglePassword(String idToken, String newPassword, String confirmPassword) {}
    public record GoogleStatus(boolean linked, String email, boolean passwordSet) {}

    public GoogleAuthController(GoogleIdTokenVerifier verifier, UserRepository users,
                                JdbcTemplate jdbc, JwtUtil jwt, RefreshTokenService refreshTokens,
                                com.softdevcode.ws_finflow.security.FieldCipher cipher,
                                PrivacyPolicySettings privacySettings) {
        this.verifier = verifier; this.users = users; this.jdbc = jdbc;
        this.jwt = jwt; this.refreshTokens = refreshTokens;
        this.cipher = cipher;
        this.privacySettings = privacySettings;
    }

    @GetMapping("/config")
    public Map<String, Object> config() {
        return Map.of("enabled", verifier.enabled(), "webClientId", verifier.webClientId());
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody GoogleCredential request) {
        var google = verifier.verify(request == null ? null : request.idToken());
        List<Long> ids = jdbc.queryForList(
            "SELECT user_id FROM public.google_account_links WHERE google_sub = ?",
            Long.class, cipher.lookup("google_account_links.google_sub",google.sub()));
        if (ids.isEmpty()) {
            if (accountExists(google.email())) return linkingRequired();
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                "code", "GOOGLE_REGISTRATION_REQUIRED",
                "error", "Crea tu cuenta con Google desde Registro para comenzar."));
        }
        User user = users.findById(ids.getFirst()).orElseThrow(() ->
            new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Cuenta no disponible"));
        return session(user);
    }

    @PostMapping("/register")
    @Transactional
    public ResponseEntity<?> register(@RequestBody GoogleRegistration request) {
        var google = verifier.verify(request == null ? null : request.idToken());
        // Serialize duplicate callbacks for this verified identity's email.
        // The lock is released with this transaction, including on rollback.
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
            (org.springframework.jdbc.core.RowCallbackHandler) row -> {}, cipher.lookup("users.email", google.email()));
        // A retry after a successful signup resumes the same account and trial.
        List<Long> linked = jdbc.queryForList(
            "SELECT user_id FROM public.google_account_links WHERE google_sub = ?",
            Long.class, cipher.lookup("google_account_links.google_sub", google.sub()));
        if (!linked.isEmpty()) return session(users.findById(linked.getFirst()).orElseThrow(() ->
            new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Cuenta no disponible")));
        // Email ownership alone never authorizes taking over an existing account.
        if (accountExists(google.email())) return linkingRequired();
        if (privacySettings.ready() && (!Boolean.TRUE.equals(request.privacyAccepted())
                || !PrivacyPolicySettings.VERSION.equals(request.privacyVersion()))) {
            return ResponseEntity.badRequest().body(Map.of("code", "PRIVACY_CONSENT_REQUIRED",
                "error", "Lee y autoriza la política de tratamiento vigente para crear tu cuenta."));
        }
        User user = new User();
        user.setEmail(google.email());
        user.setUsername(availableName(google));
        user.setPassword(User.GOOGLE_ONLY_PASSWORD);
        user.setRole("USER");
        users.saveAndFlush(user);
        insertLink(user, google);
        if (privacySettings.ready()) {
            boolean marketing = Boolean.TRUE.equals(request.marketingConsent());
            jdbc.update("INSERT INTO privacy_consents(user_id,policy_version,marketing_opt_in,source) VALUES (?,?,?,'SIGNUP')",
                user.getId(), PrivacyPolicySettings.VERSION, marketing);
            jdbc.update("INSERT INTO privacy_marketing_events(user_id,policy_version,marketing_opt_in,source) VALUES (?,?,?,'SIGNUP')",
                user.getId(), PrivacyPolicySettings.VERSION, marketing);
        }
        Instant now = Instant.now();
        jdbc.update("INSERT INTO billing_trials(user_id,started_at,expires_at) VALUES (?,?,?) ON CONFLICT (user_id) DO NOTHING",
            user.getId(), Timestamp.from(now), Timestamp.from(now.atZone(ZoneOffset.UTC).plusMonths(1).toInstant()));
        return session(user);
    }

    @GetMapping("/status")
    public GoogleStatus status(Authentication authentication) {
        User user = owner(authentication);
        List<String> emails = jdbc.queryForList(
            "SELECT google_email FROM public.google_account_links WHERE user_id = ?",
            String.class, user.getId());
        return new GoogleStatus(!emails.isEmpty(), emails.isEmpty() ? "" :
            cipher.decrypt("google_account_links.google_email",emails.getFirst()), user.hasPassword());
    }

    @PostMapping("/link")
    @Transactional
    public GoogleStatus link(Authentication authentication, @RequestBody GoogleCredential request) {
        User user = owner(authentication);
        var google = verifier.verify(request == null ? null : request.idToken());
        if (!google.email().equalsIgnoreCase(user.getEmail())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "El correo de Google debe coincidir con el correo de tu cuenta FinFlow");
        }
        List<Long> holders = jdbc.queryForList(
            "SELECT user_id FROM public.google_account_links WHERE google_sub = ?", Long.class,
                cipher.lookup("google_account_links.google_sub",google.sub()));
        if (!holders.isEmpty() && !holders.getFirst().equals(user.getId()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Esta cuenta de Google ya está vinculada");
        List<String> current = jdbc.queryForList(
            "SELECT google_sub FROM public.google_account_links WHERE user_id = ?", String.class, user.getId());
        if (!current.isEmpty() && !current.getFirst().equals(cipher.lookup("google_account_links.google_sub",google.sub())))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Desvincula primero la cuenta de Google anterior");
        if (holders.isEmpty()) {
            insertLink(user, google);
        }
        return new GoogleStatus(true, google.email(), user.hasPassword());
    }

    /** A Google-only account can create its first local password after Google reauthentication. */
    @PostMapping("/password")
    @Transactional
    public ResponseEntity<?> setPassword(Authentication authentication, @RequestBody GooglePassword request) {
        User user = owner(authentication);
        if (user.hasPassword()) throw new ResponseStatusException(HttpStatus.CONFLICT,
            "Ya tienes contraseña. Cámbiala con tu contraseña actual desde Seguridad.");
        if (request == null || request.newPassword() == null || request.confirmPassword() == null
                || request.newPassword().length() < 12 || request.newPassword().length() > 72
                || request.newPassword().getBytes(StandardCharsets.UTF_8).length > 72
                || !request.newPassword().equals(request.confirmPassword())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "La contraseña debe coincidir, tener al menos 12 caracteres y un máximo de 72 bytes UTF-8.");
        }
        var google = verifier.verify(request.idToken());
        List<Long> linked = jdbc.queryForList(
            "SELECT user_id FROM public.google_account_links WHERE google_sub = ?",
            Long.class, cipher.lookup("google_account_links.google_sub", google.sub()));
        if (linked.isEmpty() || !linked.getFirst().equals(user.getId()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Confirma la cuenta de Google vinculada a FinFlow.");
        int updated = jdbc.update("""
            UPDATE public.users SET password = ?, password_changed_at = NOW(),
                token_version = COALESCE(token_version, 0) + 1
            WHERE id = ? AND password = ?
            """, passwordEncoder.encode(request.newPassword()), user.getId(), User.GOOGLE_ONLY_PASSWORD);
        if (updated != 1) throw new ResponseStatusException(HttpStatus.CONFLICT,
            "La contraseña ya se creó. Inicia sesión de nuevo antes de cambiarla.");
        return ResponseEntity.ok(Map.of("status", "password_created", "logoutRequired", true));
    }

    @DeleteMapping("/link")
    @Transactional
    public ResponseEntity<Void> unlink(Authentication authentication) {
        User user = owner(authentication);
        if (!user.hasPassword()) throw new ResponseStatusException(HttpStatus.CONFLICT,
            "Crea primero una contraseña en Seguridad para conservar el acceso a tu cuenta.");
        jdbc.update("DELETE FROM public.google_account_links WHERE user_id = ?", user.getId());
        return ResponseEntity.noContent().build();
    }

    private void insertLink(User user, GoogleIdTokenVerifier.GoogleIdentity google) {
        jdbc.update("INSERT INTO public.google_account_links(google_sub,user_id,google_email,google_sub_cipher) VALUES (?,?,?,?)",
            cipher.lookup("google_account_links.google_sub", google.sub()), user.getId(),
            cipher.encrypt("google_account_links.google_email", google.email()),
            cipher.encrypt("google_account_links.google_sub", google.sub()));
    }

    private String availableName(GoogleIdTokenVerifier.GoogleIdentity google) {
        String base = google.name().isBlank() ? google.email().substring(0, google.email().indexOf('@')) : google.name();
        base = base.replaceAll("\\s+", " ").strip();
        if (base.length() < 2) base = "Usuario";
        base = base.substring(0, Math.min(70, base.length()));
        String name = base;
        while (users.findByUsername(name).isPresent()) name = base + " " + UUID.randomUUID().toString().substring(0, 8);
        return name;
    }

    private ResponseEntity<?> linkingRequired() {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("code", "GOOGLE_LINK_REQUIRED",
            "error", "Ya tienes una cuenta con este correo. Entra con tu contraseña y vincula Google desde Seguridad."));
    }

    private boolean accountExists(String email) {
        if (users.findByEmail(email).isPresent()) return true;
        // Older password registrations preserved email casing. Keep those
        // accounts safe too; a Google signup must never duplicate their owner.
        return users.registeredEmails().stream().anyMatch(existing -> email.equalsIgnoreCase(existing));
    }

    private ResponseEntity<?> session(User user) {
        return ResponseEntity.ok(Map.of(
            "token", jwt.generateToken(user.getEmail(), false, user.getTokenVersion() == null ? 0 : user.getTokenVersion()),
            "refreshToken", refreshTokens.issue(user),
            "user", Map.of("id", user.getId(), "name", user.getUsername(), "email", user.getEmail(), "role", user.getRole())));
    }

    private User owner(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated())
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sesión requerida");
        return users.findByEmail(authentication.getName()).orElseThrow(() ->
            new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Cuenta no disponible"));
    }
}
