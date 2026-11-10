package com.softdevcode.ws_finflow.config;

import com.softdevcode.ws_finflow.security.AuthIpRateLimiter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AuthIpRateLimitFilterTest {
    AuthIpRateLimiter limiter = mock(AuthIpRateLimiter.class);
    AuthIpRateLimitFilter filter = new AuthIpRateLimitFilter(limiter);

    @BeforeEach void enable() {
        ReflectionTestUtils.setField(filter, "enabled", true);
        ReflectionTestUtils.setField(filter, "trustRailwayRealIp", true);
    }

    private MockHttpServletRequest request(String method, String path) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setServletPath(path);
        request.setRemoteAddr("100.64.1.9");
        return request;
    }

    @Test void blocksBurstWithRetryAfterAndDoesNotCallApplication() throws Exception {
        var request = request("POST", "/auth/login");
        request.addHeader("X-Real-IP", "198.51.100.11");
        when(limiter.allow("login", "198.51.100.11", 60)).thenReturn(false);
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();
        filter.doFilter(request, response, chain);
        assertEquals(429, response.getStatus());
        assertEquals("300", response.getHeader("Retry-After"));
        assertNull(chain.getRequest());
        assertFalse(response.getContentAsString().contains("198.51.100.11"));
    }

    @Test void ignoresUntrustedXForwardedFor() throws Exception {
        var request = request("POST", "/auth/register");
        request.addHeader("X-Real-IP", "203.0.113.80");
        request.addHeader("X-Forwarded-For", "1.1.1.1");
        when(limiter.allow("register", "203.0.113.80", 20)).thenReturn(true);
        var chain = new MockFilterChain();
        filter.doFilter(request, new MockHttpServletResponse(), chain);
        assertNotNull(chain.getRequest());
        verify(limiter).allow("register", "203.0.113.80", 20);
    }

    @Test void withoutTrustedEdgeDoesNotReadSpoofedHeaders() throws Exception {
        ReflectionTestUtils.setField(filter, "trustRailwayRealIp", false);
        var request = request("POST", "/auth/login");
        request.addHeader("X-Real-IP", "8.8.8.8");
        when(limiter.allow("login", "100.64.1.9", 60)).thenReturn(true);
        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());
        verify(limiter).allow("login", "100.64.1.9", 60);
    }

    @Test void skipsOptionsGetAndUnrelatedEndpoints() throws Exception {
        for (String[] values : new String[][] {
            {"OPTIONS", "/auth/login"}, {"GET", "/auth/me"}, {"POST", "/transactions"}
        }) {
            filter.doFilter(request(values[0], values[1]), new MockHttpServletResponse(), new MockFilterChain());
        }
        verifyNoInteractions(limiter);
    }

    @Test void featureFlagOffDoesNotAffectCurrentLoginBehavior() throws Exception {
        ReflectionTestUtils.setField(filter, "enabled", false);
        var chain = new MockFilterChain();
        filter.doFilter(request("POST", "/auth/login"), new MockHttpServletResponse(), chain);
        assertNotNull(chain.getRequest());
        verifyNoInteractions(limiter);
    }
}
