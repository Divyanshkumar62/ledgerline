package com.ledgerline.infrastructure.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Gives every request a trace id in the MDC (and the {@code X-Trace-Id} response header), so all log
 * lines of one request can be found together. A caller-supplied {@code X-Request-Id} is reused if it
 * looks sane; anything else is replaced, so clients cannot inject text into our logs.
 *
 * The API-key filter adds client_id, the controller idempotency_key / source_account_id / target_account_id; this filter
 * clears every key afterwards because servlet threads are pooled and MDC is thread-local.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationFilter extends OncePerRequestFilter {

    static final String REQUEST_ID_HEADER = "X-Request-Id";
    static final String TRACE_ID_HEADER = "X-Trace-Id";
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String incoming = request.getHeader(REQUEST_ID_HEADER);
        String traceId = incoming != null && SAFE_ID.matcher(incoming).matches() ? incoming : UUID.randomUUID().toString();
        MDC.put(Mdc.TRACE_ID, traceId);
        response.setHeader(TRACE_ID_HEADER, traceId);
        try {
            chain.doFilter(request, response);
        } finally {
            Mdc.clearAll();
        }
    }

    /** MDC field names (snake_case, as feature.md §8 specifies). */
    static final class Mdc {
        static final String TRACE_ID = "trace_id";
        static final String CLIENT_ID = "client_id"; // set by ApiKeyAuthenticationFilter
        static final String IDEMPOTENCY_KEY = "idempotency_key";
        static final String SOURCE_ACCOUNT_ID = "source_account_id";
        static final String TARGET_ACCOUNT_ID = "target_account_id";

        private Mdc() {
        }

        static void clearAll() {
            MDC.remove(TRACE_ID);
            MDC.remove(CLIENT_ID);
            MDC.remove(IDEMPOTENCY_KEY);
            MDC.remove(SOURCE_ACCOUNT_ID);
            MDC.remove(TARGET_ACCOUNT_ID);
        }
    }
}
