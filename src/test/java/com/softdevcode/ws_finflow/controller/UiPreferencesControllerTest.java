package com.softdevcode.ws_finflow.controller;

import com.softdevcode.ws_finflow.entity.User;
import com.softdevcode.ws_finflow.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class UiPreferencesControllerTest {
    @Autowired UserRepository users;
    @Autowired UiPreferencesController preferences;
    @Autowired MockMvc mvc;

    @Test void missingOrInvalidCredentialsReturn401ForNativeSessionRenewal() throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/preferences/ui"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/preferences/ui")
                        .header("Authorization", "Bearer invalid-test-token"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized());
    }

    private UsernamePasswordAuthenticationToken account() {
        User user = new User();
        user.setEmail(UUID.randomUUID() + "@example.invalid");
        user.setUsername("UI test " + UUID.randomUUID());
        user.setPassword("test-only-hash"); users.saveAndFlush(user);
        return new UsernamePasswordAuthenticationToken(user.getEmail(), null);
    }

    @Test void cardVisibilityIsIndependentPerAccountAndCanBeRestored() {
        var first = account(); var second = account();
        preferences.update(first, "goals.intro", new UiPreferencesController.CardVisibility(true));
        assertEquals(List.of("goals.intro"), preferences.read(first).get("hiddenCards"));
        assertEquals(List.of(), preferences.read(second).get("hiddenCards"));
        preferences.update(first, "goals.intro", new UiPreferencesController.CardVisibility(false));
        assertEquals(List.of(), preferences.read(first).get("hiddenCards"));
    }

    @Test void unauthenticatedOrArbitraryPreferenceKeysAreRejected() {
        var account = account();
        assertThrows(ResponseStatusException.class, () -> preferences.read(null));
        assertThrows(ResponseStatusException.class, () -> preferences.update(account, "token", new UiPreferencesController.CardVisibility(true)));
        assertThrows(ResponseStatusException.class, () -> preferences.update(account, "goals.intro", new UiPreferencesController.CardVisibility(null)));
    }
}
