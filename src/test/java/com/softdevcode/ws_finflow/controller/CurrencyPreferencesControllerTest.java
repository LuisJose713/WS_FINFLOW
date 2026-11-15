package com.softdevcode.ws_finflow.controller;

import com.softdevcode.ws_finflow.entity.User;
import com.softdevcode.ws_finflow.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import java.util.UUID;

@SpringBootTest
@Transactional
class CurrencyPreferencesControllerTest {
    @Autowired CurrencyPreferencesController currencies;
    @Autowired UserRepository users;
    @Autowired UiPreferencesController ui;

    private UsernamePasswordAuthenticationToken user() {
        User u = new User();
        u.setEmail(UUID.randomUUID() + "@example.invalid");
        u.setUsername("Currency test " + UUID.randomUUID());
        u.setPassword("synthetic-test-hash");
        users.saveAndFlush(u);
        return new UsernamePasswordAuthenticationToken(u.getEmail(), null,
            java.util.List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_USER")));
    }

    @Test void userDefaultPersistsAndNeverChangesOtherAccounts() {
        var a = user(); var b = user();
        assertEquals("COP", currencies.read(a).get("currency"));
        assertEquals("USD", currencies.update(a, new CurrencyPreferencesController.CurrencyUpdate("usd")).get("currency"));
        assertEquals("USD", currencies.read(a).get("currency"));
        assertEquals("COP", currencies.read(b).get("currency"));
        assertEquals("EUR", currencies.update(a, new CurrencyPreferencesController.CurrencyUpdate("EUR")).get("currency"));
        assertEquals("EUR", currencies.read(a).get("currency"));
        assertEquals("COP", currencies.update(a, new CurrencyPreferencesController.CurrencyUpdate("COP")).get("currency"));
        assertEquals("COP", currencies.read(a).get("currency"));
    }

    @Test void currencyPreferenceCannotHideCardsOrBypassAuth() {
        var a = user();
        ui.update(a, "goals.intro", new UiPreferencesController.CardVisibility(true));
        currencies.update(a, new CurrencyPreferencesController.CurrencyUpdate("CAD"));
        assertEquals(java.util.List.of("goals.intro"), ui.read(a).get("hiddenCards"));
        assertThrows(ResponseStatusException.class, () -> currencies.read(null));
        assertThrows(ResponseStatusException.class, () -> currencies.update(null, new CurrencyPreferencesController.CurrencyUpdate("USD")));
        assertThrows(ResponseStatusException.class, () -> currencies.update(a, new CurrencyPreferencesController.CurrencyUpdate("BTC")));
        assertThrows(ResponseStatusException.class, () -> currencies.update(a, new CurrencyPreferencesController.CurrencyUpdate(null)));
        assertEquals("CAD", currencies.read(a).get("currency"));
    }
}
