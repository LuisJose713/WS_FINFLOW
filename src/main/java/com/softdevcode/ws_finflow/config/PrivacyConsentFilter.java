package com.softdevcode.ws_finflow.config;

import com.softdevcode.ws_finflow.entity.User;
import com.softdevcode.ws_finflow.repository.UserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Enforced only after the legally reviewed policy is explicitly published.
 * Never revokes JWTs just because a new privacy policy was introduced.
 */
@Component
@RequiredArgsConstructor
public class PrivacyConsentFilter extends OncePerRequestFilter {
    private final JdbcTemplate jdbc;
    private final UserRepository users;
    private final PrivacyPolicySettings settings;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!settings.ready() || "OPTIONS".equalsIgnoreCase(request.getMethod())) return true;
        String path = request.getServletPath();
        return path.startsWith("/privacy/")
            || path.startsWith("/actuator/")
            || path.equals("/auth/login")
            || path.equals("/auth/register")
            || path.equals("/auth/refresh")
            || path.equals("/auth/session")
            || path.equals("/auth/logout")
            || path.equals("/auth/me")
            || path.equals("/auth/change-password")
            || path.equals("/auth/revoke-sessions")
            || path.equals("/billing/catalog")
            || path.equals("/billing/quote");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && auth.getName() != null) {
            User user = users.findByEmail(auth.getName()).orElse(null);
            if (user != null) {
                Long count = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM privacy_consents
                     WHERE user_id = ? AND policy_version = ?
                    """, Long.class, user.getId(), PrivacyPolicySettings.VERSION);
                if (count == null || count == 0) {
                    response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                    response.setHeader("Cache-Control", "no-store");
                    response.setContentType("application/json;charset=UTF-8");
                    response.getWriter().write("{\"error\":\"PRIVACY_CONSENT_REQUIRED\",\"policyVersion\":\""
                            + PrivacyPolicySettings.VERSION + "\"}");
                    return;
                }
            }
        }
        chain.doFilter(request, response);
    }
}
