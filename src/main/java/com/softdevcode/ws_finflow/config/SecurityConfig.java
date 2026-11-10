package com.softdevcode.ws_finflow.config;
import org.springframework.http.HttpMethod;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
public class SecurityConfig {

    private final JwtFilter jwtFilter;
    private final PrivacyConsentFilter privacyFilter;

    @Value("${FINFLOW_CORS_ALLOWED_ORIGINS:https://finflow-opal-five.vercel.app}")
    private String allowedOrigins;

    public SecurityConfig(JwtFilter jwtFilter, PrivacyConsentFilter privacyFilter) {
        this.jwtFilter = jwtFilter;
        this.privacyFilter = privacyFilter;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS)
                )
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/auth/login", "/auth/register").permitAll()
                        .requestMatchers(HttpMethod.GET, "/billing/catalog", "/billing/quote", "/privacy/policy", "/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers("/admin", "/admin/**").hasRole("ADMIN")
                        .anyRequest().authenticated()
                )
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(privacyFilter, JwtFilter.class);

        return http.build();
    }

    // Prevent the same filter from running outside the Spring Security chain.
    @Bean
    public FilterRegistrationBean<PrivacyConsentFilter> privacyFilterRegistration(PrivacyConsentFilter filter) {
        FilterRegistrationBean<PrivacyConsentFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        // Production only trusts explicitly configured origins. No wildcard preview domains.
        // Temporary QA domains must be supplied deliberately as comma-separated values.
        List<String> allowed = java.util.Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(origin -> !origin.isEmpty())
                .toList();
        config.setAllowedOrigins(allowed);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type", "Accept", "X-Client-Type", "X-Requested-With"));
        config.setAllowCredentials(false);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

}