package com.softdevcode.ws_finflow.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.softdevcode.ws_finflow.config.PrivacyPolicySettings;
import com.softdevcode.ws_finflow.entity.User;
import com.softdevcode.ws_finflow.repository.UserRepository;
import com.softdevcode.ws_finflow.security.GoogleIdTokenVerifier;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Runs only against the disposable PostgreSQL database created by backend CI. */
@EnabledIfEnvironmentVariable(named = "SPRING_JPA_HIBERNATE_DDL_AUTO", matches = "create-drop")
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class GoogleRegistrationIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserRepository users;
    @Autowired EntityManager entities;
    @MockBean GoogleIdTokenVerifier verifier;
    @MockBean PrivacyPolicySettings privacy;
    String email;

    @BeforeEach void prepareDisposableSchemaAndGoogleProof() {
        jdbc.execute("ALTER TABLE public.google_account_links ADD COLUMN IF NOT EXISTS google_sub_cipher TEXT");
        jdbc.execute("CREATE TABLE IF NOT EXISTS billing_trials (user_id BIGINT PRIMARY KEY, started_at TIMESTAMPTZ, expires_at TIMESTAMPTZ)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS privacy_consents (user_id BIGINT, policy_version TEXT, marketing_opt_in BOOLEAN, source TEXT)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS privacy_marketing_events (user_id BIGINT, policy_version TEXT, marketing_opt_in BOOLEAN, source TEXT)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS auth_rate_limits (subject_hash CHAR(64) PRIMARY KEY, window_started_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP, failed_count INTEGER NOT NULL DEFAULT 0, locked_until TIMESTAMPTZ)");
        email = "google-" + UUID.randomUUID() + "@example.invalid";
        when(verifier.verify("verified-test-proof")).thenReturn(
            new GoogleIdTokenVerifier.GoogleIdentity("1234567890", email, "Google QA " + UUID.randomUUID()));
        when(privacy.ready()).thenReturn(true);
    }

    private String signup() throws Exception {
        return mvc.perform(post("/auth/google/register").contentType("application/json").content(mapper.writeValueAsString(Map.of(
            "idToken", "verified-test-proof", "privacyAccepted", true, "privacyVersion", PrivacyPolicySettings.VERSION))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.user.role").value("USER"))
            .andReturn().getResponse().getContentAsString();
    }

    @Test void anonymousGoogleSignupCreatesOneOwnerOneLinkOneTrialAndAUsableSession() throws Exception {
        var first = mapper.readTree(signup());
        long owner = first.at("/user/id").asLong();
        assertFalse(users.findById(owner).orElseThrow().hasPassword());
        assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM google_account_links WHERE user_id=?", Long.class, owner));
        assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM privacy_consents WHERE user_id=?", Long.class, owner));
        assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM billing_trials WHERE user_id=?", Long.class, owner));
        var retry = mapper.readTree(signup());
        assertEquals(owner, retry.at("/user/id").asLong());
        assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM billing_trials WHERE user_id=?", Long.class, owner));
        mvc.perform(get("/auth/me").header("Authorization", "Bearer " + first.get("token").asText()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(owner));
        mvc.perform(post("/auth/google/login").contentType("application/json").content("{\"idToken\":\"verified-test-proof\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.user.id").value(owner));
    }

    @Test void missingConsentDoesNotCreateAnyUserOrLink() throws Exception {
        mvc.perform(post("/auth/google/register").contentType("application/json").content("{\"idToken\":\"verified-test-proof\"}"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("PRIVACY_CONSENT_REQUIRED"));
        assertTrue(users.findByEmail(email).isEmpty());
    }

    @Test void existingMixedCaseEmailIsProtectedWithoutChangingItsPassword() throws Exception {
        User existing = new User(); existing.setEmail(email.toUpperCase(java.util.Locale.ROOT));
        existing.setUsername("Existing " + UUID.randomUUID());
        String hash = new BCryptPasswordEncoder().encode("existing-password"); existing.setPassword(hash);
        users.saveAndFlush(existing);
        mvc.perform(post("/auth/google/register").contentType("application/json").content(mapper.writeValueAsString(Map.of(
            "idToken", "verified-test-proof", "privacyAccepted", true, "privacyVersion", PrivacyPolicySettings.VERSION))))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("GOOGLE_LINK_REQUIRED"));
        assertEquals(hash, users.findById(existing.getId()).orElseThrow().getPassword());
        assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM google_account_links WHERE user_id=?", Long.class, existing.getId()));
    }

    @Test void googleOnlyOwnerCanCreatePasswordAndOldSessionIsRevoked() throws Exception {
        var first = mapper.readTree(signup());
        String token = first.get("token").asText();
        mvc.perform(delete("/auth/google/link").header("Authorization", "Bearer " + token))
            .andExpect(status().isConflict());
        mvc.perform(post("/auth/google/password").header("Authorization", "Bearer " + token)
            .contentType("application/json").content(mapper.writeValueAsString(Map.of("idToken", "verified-test-proof",
                "newPassword", "new-password-123", "confirmPassword", "new-password-123"))))
            .andExpect(status().isOk());
        entities.clear();
        mvc.perform(get("/auth/me").header("Authorization", "Bearer " + token)).andExpect(status().isUnauthorized());
        mvc.perform(post("/auth/login").contentType("application/json").content(mapper.writeValueAsString(Map.of(
            "email", email, "password", "new-password-123"))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.user.id").value(first.at("/user/id").asLong()));
    }
}
