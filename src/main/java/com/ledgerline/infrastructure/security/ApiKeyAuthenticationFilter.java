package com.ledgerline.infrastructure.security;

import com.ledgerline.application.service.ApiClientService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * {@code Authorization: Bearer <api key>} → the {@link com.ledgerline.domain.model.ApiClient} becomes the
 * authenticated principal with ROLE_CLIENT or ROLE_ADMIN. A missing or unknown key leaves the request
 * anonymous, and the security rules then answer 401.
 *
 * Deliberately NOT a Spring bean: Boot would also register a Filter bean as a plain servlet filter, so it
 * would run twice. It is added to the security chain only, in {@link SecurityConfig}.
 */
class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER = "Bearer ";

    private final ApiClientService clients;

    ApiKeyAuthenticationFilter(ApiClientService clients) {
        this.clients = clients;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith(BEARER)) {
            clients.authenticate(header.substring(BEARER.length()).trim()).ifPresent(client -> {
                SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                        client, null, List.of(new SimpleGrantedAuthority("ROLE_" + client.role().name()))));
                MDC.put("client_id", client.clientId()); // cleared by CorrelationFilter
            });
        }
        chain.doFilter(request, response);
    }
}
