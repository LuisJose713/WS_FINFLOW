package com.softdevcode.ws_finflow.config;

import com.softdevcode.ws_finflow.security.AuthIpRateLimiter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Defense-in-depth per-source limiter in addition to per-account throttling.
 * Enable only after confirming Railway's public edge is the sole ingress.
 * X-Real-IP is authoritative only on Railway's public HTTP edge, never on
 * private networking or TCP proxies; do not trust X-Forwarded-For.
 */
@Component
@RequiredArgsConstructor
public class AuthIpRateLimitFilter extends OncePerRequestFilter {
    private final AuthIpRateLimiter limiter;

    @Value("${FINFLOW_AUTH_IP_RATE_ENABLED:false}")
    private boolean enabled;

    @Value("${FINFLOW_TRUST_RAILWAY_REAL_IP:false}")
    private boolean trustRailwayRealIp;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!enabled || !"POST".equalsIgnoreCase(request.getMethod())) return true;
        String path = request.getServletPath();
        return !("/auth/login".equals(path) || "/auth/register".equals(path));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String action = "/auth/login".equals(request.getServletPath()) ? "login" : "register";
        int maximum = "login".equals(action) ? 60 : 20;
        String source = sourceIp(request);

        if (!limiter.allow(action, source, maximum)) {
            response.setStatus(HttpServletResponse.SC_TOO_MANY_REQUESTS);
            response.setContentType("application/json;charset=UTF-8");
            response.setHeader("Retry-After", "300");
            response.setHeader("Cache-Control", "no-store");
            response.getWriter().write("{\"error\":\"Demasiadas solicitudes. Intenta más tarde.\"}");
            return;
        }
        chain.doFilter(request, response);
    }

    String sourceIp(HttpServletRequest request) {
        if (trustRailwayRealIp) {
            String realIp = request.getHeader("X-Real-IP");
            // Railway overwrites this header at its public HTTP edge. Never
            // read arbitrary client-supplied Forwarded, XFF or CF headers.
            if (realIp != null && realIp.length() <= 45 &&
                    realIp.matches("(?i)[0-9a-f:.]{3,45}")) {
                return realIp;
            }
        }
        // Safe fallback if Railway edge-trust is not explicitly configured.
        return request.getRemoteAddr();
    }
}
