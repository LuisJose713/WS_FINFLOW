package com.softdevcode.ws_finflow.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.cors.CorsConfiguration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class CorsAllowlistTest {

    @Test
    void configuredOriginsAreExactAndNeverWildcards() {
        SecurityConfig security = new SecurityConfig(mock(JwtFilter.class), mock(PrivacyConsentFilter.class));
        ReflectionTestUtils.setField(security, "allowedOrigins",
                " https://finflow-opal-five.vercel.app , https://approved-preview.vercel.app ");

        CorsConfiguration cors = security.corsConfigurationSource()
                .getCorsConfiguration(new MockHttpServletRequest("OPTIONS", "/auth/login"));

        assertNotNull(cors);
        assertEquals(2, cors.getAllowedOrigins().size());
        assertTrue(cors.getAllowedOrigins().contains("https://finflow-opal-five.vercel.app"));
        assertEquals("https://finflow-opal-five.vercel.app",
                cors.checkOrigin("https://finflow-opal-five.vercel.app"));
        assertNull(cors.checkOrigin("https://finflow-not-approved.vercel.app"));
        assertNull(cors.checkOrigin("https://attacker.example"));
        assertNull(cors.getAllowedOriginPatterns());
        assertEquals(Boolean.FALSE, cors.getAllowCredentials());
    }
}
