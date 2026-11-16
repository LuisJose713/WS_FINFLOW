package com.softdevcode.ws_finflow.controller;

import com.softdevcode.ws_finflow.config.PrivacyPolicySettings;
import com.softdevcode.ws_finflow.entity.User;
import com.softdevcode.ws_finflow.repository.UserRepository;
import com.softdevcode.ws_finflow.security.FieldCipher;
import com.softdevcode.ws_finflow.security.GoogleIdTokenVerifier;
import com.softdevcode.ws_finflow.security.JwtUtil;
import com.softdevcode.ws_finflow.security.RefreshTokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GoogleAuthControllerTest {
    @Mock GoogleIdTokenVerifier verifier;
    @Mock UserRepository users;
    @Mock JdbcTemplate jdbc;
    @Mock JwtUtil jwt;
    @Mock RefreshTokenService refreshTokens;
    @Mock PrivacyPolicySettings privacy;
    final FieldCipher cipher = new FieldCipher(Base64.getEncoder().encodeToString(new byte[32]), true);
    final GoogleIdTokenVerifier.GoogleIdentity identity =
        new GoogleIdTokenVerifier.GoogleIdentity("1234567890", "new@example.invalid", "Luis José");
    GoogleAuthController controller;

    @BeforeEach void setup() {
        controller = new GoogleAuthController(verifier, users, jdbc, jwt, refreshTokens, cipher, privacy);
        lenient().when(verifier.verify("google-proof")).thenReturn(identity);
    }

    private User user(boolean password) {
        User user = new User();
        user.setId(77L); user.setEmail(identity.email()); user.setUsername(identity.name());
        user.setPassword(password ? new BCryptPasswordEncoder().encode("current-password") : User.GOOGLE_ONLY_PASSWORD);
        return user;
    }

    private void sessionFor(User user) {
        when(jwt.generateToken(user.getEmail(), false, user.getTokenVersion())).thenReturn("finflow-session");
        when(refreshTokens.issue(user)).thenReturn("refresh-credential");
    }

    private GoogleAuthController.GoogleRegistration signup(Boolean accepted, String version) {
        return new GoogleAuthController.GoogleRegistration("google-proof", accepted, version, false);
    }

    @Test void registersNewGoogleOwnerWithoutPriorPasswordAndEncryptsTheLink() {
        when(privacy.ready()).thenReturn(true);
        when(users.saveAndFlush(any(User.class))).thenAnswer(call -> {
            User saved = call.getArgument(0); saved.setId(77L); return saved;
        });
        when(jwt.generateToken(identity.email(), false, 0)).thenReturn("finflow-session");
        when(refreshTokens.issue(any())).thenReturn("refresh-credential");

        var response = controller.register(signup(true, PrivacyPolicySettings.VERSION));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        var owner = ArgumentCaptor.forClass(User.class);
        verify(users).saveAndFlush(owner.capture());
        assertEquals("USER", owner.getValue().getRole());
        assertEquals(identity.email(), owner.getValue().getEmail());
        assertFalse(owner.getValue().hasPassword());
        verify(jdbc).update(contains("INSERT INTO public.google_account_links"),
            eq(cipher.lookup("google_account_links.google_sub", identity.sub())), eq(77L),
            startsWith(FieldCipher.PREFIX), startsWith(FieldCipher.PREFIX));
        verify(jdbc).update(contains("INSERT INTO privacy_consents"), eq(77L), eq(PrivacyPolicySettings.VERSION), eq(false));
        verify(jdbc).update(contains("INSERT INTO privacy_marketing_events"), eq(77L), eq(PrivacyPolicySettings.VERSION), eq(false));
        verify(jdbc).update(contains("INSERT INTO billing_trials"), eq(77L), any(java.sql.Timestamp.class), any(java.sql.Timestamp.class));
        assertEquals("finflow-session", ((Map<?, ?>) response.getBody()).get("token"));
    }

    @Test void neverLinksAnExistingPasswordAccountByEmailDuringSignup() {
        User existing = user(true);
        String hash = existing.getPassword();
        when(users.findByEmail(identity.email())).thenReturn(Optional.of(existing));
        var response = controller.register(signup(true, PrivacyPolicySettings.VERSION));
        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertEquals("GOOGLE_LINK_REQUIRED", ((Map<?, ?>) response.getBody()).get("code"));
        assertEquals(hash, existing.getPassword());
        verify(users, never()).saveAndFlush(any());
        verifyNoInteractions(jwt, refreshTokens);
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test void missingOrStaleConsentDoesNotCreateAnAccount() {
        when(privacy.ready()).thenReturn(true);
        for (var request : List.of(signup(false, PrivacyPolicySettings.VERSION), signup(true, "old-version"))) {
            var response = controller.register(request);
            assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
            assertEquals("PRIVACY_CONSENT_REQUIRED", ((Map<?, ?>) response.getBody()).get("code"));
        }
        verify(users, never()).saveAndFlush(any());
        verifyNoInteractions(jwt, refreshTokens);
    }

    @Test void aSignupRetryUsesTheOriginalOwnerWithoutNewTrialOrDuplicateAccount() {
        User existing = user(false);
        when(jdbc.queryForList(anyString(), eq(Long.class), anyString())).thenReturn(List.of(77L));
        when(users.findById(77L)).thenReturn(Optional.of(existing));
        sessionFor(existing);
        var response = controller.register(signup(null, null));
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(77L, ((Map<?, ?>) ((Map<?, ?>) response.getBody()).get("user")).get("id"));
        verify(users, never()).saveAndFlush(any());
        verify(users, never()).findByEmail(anyString());
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test void aNewGoogleLoginRequestsRegistrationRatherThanAnImpossiblePriorLink() {
        var response = controller.login(new GoogleAuthController.GoogleCredential("google-proof"));
        assertEquals("GOOGLE_REGISTRATION_REQUIRED", ((Map<?, ?>) response.getBody()).get("code"));
        verifyNoInteractions(jwt, refreshTokens);
    }

    @Test void anExistingUnlinkedLoginStillRequiresPasswordAndExplicitLinking() {
        when(users.findByEmail(identity.email())).thenReturn(Optional.of(user(true)));
        var response = controller.login(new GoogleAuthController.GoogleCredential("google-proof"));
        assertEquals("GOOGLE_LINK_REQUIRED", ((Map<?, ?>) response.getBody()).get("code"));
        verifyNoInteractions(jwt, refreshTokens);
    }

    @Test void emailCasingCannotDuplicateAnExistingEncryptedAccount() {
        when(users.registeredEmails()).thenReturn(List.of("New@Example.invalid"));
        var response = controller.register(signup(true, PrivacyPolicySettings.VERSION));
        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertEquals("GOOGLE_LINK_REQUIRED", ((Map<?, ?>) response.getBody()).get("code"));
        verify(users, never()).saveAndFlush(any());
        verifyNoInteractions(jwt, refreshTokens);
    }

    @Test void invalidGoogleProofCannotReachAccountCreation() {
        when(verifier.verify("forged")).thenThrow(new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        assertThrows(ResponseStatusException.class, () -> controller.register(
            new GoogleAuthController.GoogleRegistration("forged", true, PrivacyPolicySettings.VERSION, false)));
        verifyNoInteractions(users, jdbc, jwt, refreshTokens);
    }

    @Test void duplicateDisplayNamesDoNotBlockIndependentGoogleOwners() {
        when(users.findByUsername(identity.name())).thenReturn(Optional.of(user(true)));
        when(users.saveAndFlush(any())).thenAnswer(call -> { User u = call.getArgument(0); u.setId(78L); return u; });
        when(jwt.generateToken(identity.email(), false, 0)).thenReturn("finflow-session");
        when(refreshTokens.issue(any())).thenReturn("refresh-credential");
        assertEquals(HttpStatus.OK, controller.register(signup(null, null)).getStatusCode());
        var saved = ArgumentCaptor.forClass(User.class);
        verify(users).saveAndFlush(saved.capture());
        assertTrue(saved.getValue().getUsername().startsWith(identity.name() + " "));
    }

    @Test void cannotRemoveTheOnlyLoginMethod() {
        when(users.findByEmail(identity.email())).thenReturn(Optional.of(user(false)));
        var auth = new UsernamePasswordAuthenticationToken(identity.email(), null, List.of());
        var error = assertThrows(ResponseStatusException.class, () -> controller.unlink(auth));
        assertEquals(HttpStatus.CONFLICT, error.getStatusCode());
        verifyNoInteractions(jdbc);
    }

    @Test void firstPasswordRequiresTheLinkedGoogleSubjectAndRevokesOldSessionVersion() {
        User owner = user(false);
        when(users.findByEmail(identity.email())).thenReturn(Optional.of(owner));
        when(jdbc.queryForList(anyString(), eq(Long.class), anyString())).thenReturn(List.of(77L));
        when(jdbc.update(contains("UPDATE public.users SET password"), anyString(), eq(77L), eq(User.GOOGLE_ONLY_PASSWORD))).thenReturn(1);
        var auth = new UsernamePasswordAuthenticationToken(identity.email(), null, List.of());
        var response = controller.setPassword(auth,
            new GoogleAuthController.GooglePassword("google-proof", "new-password-123", "new-password-123"));
        assertEquals(HttpStatus.OK, response.getStatusCode());
        var hash = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(contains("token_version = COALESCE(token_version, 0) + 1"), hash.capture(), eq(77L), eq(User.GOOGLE_ONLY_PASSWORD));
        assertTrue(new BCryptPasswordEncoder().matches("new-password-123", hash.getValue()));
    }

    @Test void aConcurrentFirstPasswordCannotOverwriteOneAlreadyCreated() {
        User owner = user(false);
        when(users.findByEmail(identity.email())).thenReturn(Optional.of(owner));
        when(jdbc.queryForList(anyString(), eq(Long.class), anyString())).thenReturn(List.of(77L));
        var auth = new UsernamePasswordAuthenticationToken(identity.email(), null, List.of());
        var error = assertThrows(ResponseStatusException.class, () -> controller.setPassword(auth,
            new GoogleAuthController.GooglePassword("google-proof", "new-password-123", "new-password-123")));
        assertEquals(HttpStatus.CONFLICT, error.getStatusCode());
        verify(users, never()).saveAndFlush(any());
    }

    @Test void anotherGoogleIdentityCannotSetTheOwnersPassword() {
        User owner = user(false);
        when(users.findByEmail(identity.email())).thenReturn(Optional.of(owner));
        when(jdbc.queryForList(anyString(), eq(Long.class), anyString())).thenReturn(List.of(88L));
        var auth = new UsernamePasswordAuthenticationToken(identity.email(), null, List.of());
        var error = assertThrows(ResponseStatusException.class, () -> controller.setPassword(auth,
            new GoogleAuthController.GooglePassword("google-proof", "new-password-123", "new-password-123")));
        assertEquals(HttpStatus.FORBIDDEN, error.getStatusCode());
        assertFalse(owner.hasPassword());
        verify(users, never()).saveAndFlush(any());
    }

    @Test void googleReauthenticationCannotReplaceAnExistingLocalPassword() {
        User owner = user(true);
        String original = owner.getPassword();
        when(users.findByEmail(identity.email())).thenReturn(Optional.of(owner));
        var auth = new UsernamePasswordAuthenticationToken(identity.email(), null, List.of());
        var error = assertThrows(ResponseStatusException.class, () -> controller.setPassword(auth,
            new GoogleAuthController.GooglePassword("google-proof", "new-password-123", "new-password-123")));
        assertEquals(HttpStatus.CONFLICT, error.getStatusCode());
        assertEquals(original, owner.getPassword());
        verifyNoInteractions(verifier, jdbc);
    }
}
