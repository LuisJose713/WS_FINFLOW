package com.softdevcode.ws_finflow.controller;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VoiceDraftControllerTest {
    @Test void spanishExpenseRecognizesThirtyFiveThousandPesos() {
        var draft = VoiceDraftController.parse("Gasté 35.000 pesos en almuerzo", "USD");
        assertEquals("expense", draft.type());
        assertEquals("COP", draft.currency());
        assertEquals(35000d, draft.amount());
        assertFalse(draft.requiresConversion());
        assertTrue(draft.title().contains("almuerzo"));
    }

    @Test void importedAmountsCannotBeMistakenForCOP() {
        var draft = VoiceDraftController.parse("Pagué 20 dólares por café", "COP");
        assertEquals("USD", draft.currency());
        assertEquals(20d, draft.amount());
        assertTrue(draft.requiresConversion());
    }

    @Test void defaultCurrencyAppliesWhenSpokenCurrencyMissing() {
        var draft = VoiceDraftController.parse("Recibí 1.250,50 por trabajo", "EUR");
        assertEquals("income", draft.type());
        assertEquals(1250.50d, draft.amount());
        assertEquals("EUR", draft.currency());
        assertTrue(draft.requiresConversion());
    }

    @Test void uncertainSpeechRemainsDraftWithoutWrites() {
        var draft = VoiceDraftController.parse("35 mil almuerzo", "COP");
        assertNull(draft.type());
        assertEquals(35000d, draft.amount());
        assertTrue(draft.warning().contains("Confirma"));
    }
}
