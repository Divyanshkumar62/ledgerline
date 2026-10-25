package com.ledgerline.infrastructure.security;

import com.ledgerline.application.service.ApiClientService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import java.io.IOException;

/**
 * API-key security (ADR-011). Stateless: no sessions, no cookies, so CSRF protection (which defends
 * cookie-authenticated browsers) does not apply and is disabled.
 *
 * <pre>
 * /actuator/health/**, /actuator/info, /actuator/prometheus   public (probes, scraping; restrict at the network edge)
 * /api/v1/admin/**                                            ROLE_ADMIN
 * /api/**                                                     ROLE_CLIENT or ROLE_ADMIN
 * everything else                                             denied
 * </pre>
 */
@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain api(HttpSecurity http, ApiClientService clients) throws Exception {
        http.csrf(csrf -> csrf.disable())
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .logout(logout -> logout.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info", "/actuator/prometheus", "/error").permitAll()
                        .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                        .requestMatchers("/api/**").hasAnyRole("CLIENT", "ADMIN")
                        .anyRequest().denyAll())
                .exceptionHandling(e -> e
                        .authenticationEntryPoint((request, response, ex) -> {
                            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
                            problem(response, HttpServletResponse.SC_UNAUTHORIZED, "Unauthorized",
                                    "Send a valid API key: Authorization: Bearer <key>");
                        })
                        .accessDeniedHandler((request, response, ex) -> problem(response, HttpServletResponse.SC_FORBIDDEN,
                                "Forbidden", "This API key is not allowed to call this endpoint")))
                .addFilterBefore(new ApiKeyAuthenticationFilter(clients), UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    /** No username/password users exist. Declaring this stops Boot from generating a default user and password. */
    @Bean
    UserDetailsService noPasswordUsers() {
        return username -> {
            throw new UsernameNotFoundException(username);
        };
    }

    private static void problem(HttpServletResponse response, int status, String title, String detail) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.getWriter().write("""
                {"type":"https://ledgerline.com/errors/%s","title":"%s","status":%d,"detail":"%s"}"""
                .formatted(title.toLowerCase(), title, status, detail));
    }
}
