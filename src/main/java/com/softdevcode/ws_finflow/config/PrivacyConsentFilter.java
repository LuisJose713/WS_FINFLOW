package com.softdevcode.ws_finflow.config;

import com.softdevcode.ws_finflow.controller.PrivacyController;
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

/** Applied AFTER JwtFilter, BEFORE AuthorizationFilter. */
@Component
@RequiredArgsConstructor
public class PrivacyConsentFilter extends OncePerRequestFilter {
    private final JdbcTemplate jdbc;
    private final UserRepository users;
    private final PrivacyController privacy;

    @Override
    protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,FilterChain chain)
            throws ServletException,IOException {
        String path = req.getServletPath();
        if ("OPTIONS".equalsIgnoreCase(req.getMethod())
              || path.startsWith("/privacy/")
              || path.equals("/auth/login")
              || path.equals("/auth/register")
              || path.equals("/auth/me")
              || path.equals("/auth/change-password")
              || path.equals("/auth/profile-email/request")
              || path.equals("/auth/profile-email/confirm")
              || path.equals("/auth/revoke-sessions")
              || path.equals("/billing/catalog")
              || path.equals("/billing/quote")
              || path.equals("/billing/me")
              || !privacy.ready()) {
            chain.doFilter(req,res); return;
        }
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if(auth != null && auth.isAuthenticated()) {
            User user = users.findByEmail(auth.getName()).orElse(null);
            if(user != null) {
                Long count = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM privacy_consents WHERE user_id=? AND policy_version=?
                    """,Long.class,user.getId(),PrivacyController.POLICY_VERSION);
                if(count == null || count == 0) {
                    res.setStatus(HttpServletResponse.SC_FORBIDDEN);
                    res.setContentType("application/json;charset=UTF-8");
                    res.setHeader("Cache-Control","no-store");
                    res.getWriter().write("{\"error\":\"PRIVACY_CONSENT_REQUIRED\",\"policyVersion\":\""
                            +PrivacyController.POLICY_VERSION+"\"}");
                    return;
                }
            }
        }
        chain.doFilter(req,res);
    }
}
