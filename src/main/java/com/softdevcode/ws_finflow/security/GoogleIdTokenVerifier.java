package com.softdevcode.ws_finflow.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Arrays;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** A Google ID token is verified cryptographically, not accepted as a profile assertion. */
@Service
public class GoogleIdTokenVerifier {
    private static final Logger log = LoggerFactory.getLogger(GoogleIdTokenVerifier.class);
    private static final String JWK_SET = "https://www.googleapis.com/oauth2/v3/certs";
    private final Set<String> allowedAudiences;
    private final String webClientId;
    private final NimbusJwtDecoder decoder;

    public record GoogleIdentity(String sub, String email, String name) {}

    public GoogleIdTokenVerifier(
            @Value("${FINFLOW_GOOGLE_WEB_CLIENT_ID:}") String webClient,
            @Value("${FINFLOW_GOOGLE_CLIENT_IDS:}") String otherClients) {
        this.webClientId = webClient == null ? "" : webClient.strip();
        this.allowedAudiences = new HashSet<>();
        if (!this.webClientId.isBlank()) allowedAudiences.add(this.webClientId);
        if (otherClients != null) Arrays.stream(otherClients.split(",")).map(String::strip)
                .filter(value -> !value.isEmpty()).forEach(allowedAudiences::add);
        // Nimbus pins RS256, verifies Google's signature, and refreshes rotating JWKS.
        this.decoder = NimbusJwtDecoder.withJwkSetUri(JWK_SET).build();
        this.decoder.setJwtValidator(JwtValidators.createDefault());
    }

    public boolean enabled() { return !allowedAudiences.isEmpty(); }
    public String webClientId() { return webClientId; }

    public GoogleIdentity verify(String rawToken) {
        if (!enabled()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Acceso con Google no configurado");
        if (rawToken == null || rawToken.length() < 100 || rawToken.length() > 8192)
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Credencial de Google inválida");
        try {
            Jwt jwt = decoder.decode(rawToken);
            String issuer = jwt.getIssuer() == null ? "" : jwt.getIssuer().toString();
            if (!"accounts.google.com".equals(issuer) && !"https://accounts.google.com".equals(issuer))
                throw new IllegalArgumentException("Untrusted Google issuer");
            if (jwt.getAudience().stream().noneMatch(allowedAudiences::contains))
                throw new IllegalArgumentException("Token audience not issued for FinFlow");
            if (!Boolean.TRUE.equals(jwt.getClaim("email_verified")))
                throw new IllegalArgumentException("Google email not verified");
            String subject = jwt.getSubject();
            String email = jwt.getClaimAsString("email");
            if (subject == null || !subject.matches("[0-9]{5,255}")
                    || email == null || email.length() > 254 || !email.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$"))
                throw new IllegalArgumentException("Malformed Google identity");
            String name = jwt.getClaimAsString("name");
            return new GoogleIdentity(subject, email.strip().toLowerCase(Locale.ROOT),
                    name == null ? "" : name.strip());
        } catch (JwtException | IllegalArgumentException ex) {
            // Record only the validation category, never tokens, emails or personal data.
            String reason = ex.getMessage() == null ? "" : ex.getMessage();
            String category = reason.contains("audience") ? "AUDIENCE_MISMATCH"
                    : reason.contains("issuer") ? "ISSUER_MISMATCH"
                    : reason.contains("email not verified") ? "EMAIL_NOT_VERIFIED"
                    : reason.contains("Malformed Google identity") ? "IDENTITY_INVALID"
                    : ex instanceof JwtException ? "JWT_SIGNATURE_OR_CLAIMS" : "CLAIMS_INVALID";
            log.warn("Google identity verification rejected: {}", category);
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Credencial de Google inválida");
        }
    }
}
