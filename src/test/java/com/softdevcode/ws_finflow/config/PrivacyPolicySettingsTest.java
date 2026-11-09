package com.softdevcode.ws_finflow.config;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;

class PrivacyPolicySettingsTest {
    @Test
    void neverPublishesMissingLegalIdentity() {
        var settings = new PrivacyPolicySettings();
        assertFalse(settings.ready());
        ReflectionTestUtils.setField(settings, "published", true);
        assertFalse(settings.ready(), "An approval flag alone cannot publish a policy");
    }

    @Test
    void requiresAllControllerFieldsAndExplicitPublication() {
        var settings = new PrivacyPolicySettings();
        ReflectionTestUtils.setField(settings, "controllerName", "Example Controller");
        ReflectionTestUtils.setField(settings, "contactEmail", "privacy@example.org");
        ReflectionTestUtils.setField(settings, "contactAddress", "Example city address");
        ReflectionTestUtils.setField(settings, "contactPhone", "+57 300 000 0000");
        assertFalse(settings.ready(), "Legal contact details do not auto-publish");
        ReflectionTestUtils.setField(settings, "published", true);
        assertTrue(settings.ready());
    }

    @Test
    void rejectsInvalidContactEmail() {
        var settings = new PrivacyPolicySettings();
        ReflectionTestUtils.setField(settings, "controllerName", "Example Controller");
        ReflectionTestUtils.setField(settings, "contactEmail", "invalid");
        ReflectionTestUtils.setField(settings, "contactAddress", "Example city address");
        ReflectionTestUtils.setField(settings, "contactPhone", "12345");
        ReflectionTestUtils.setField(settings, "published", true);
        assertFalse(settings.ready());
    }
}
