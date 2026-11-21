package com.ledgerline.infrastructure.redis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerline.application.dto.TransferResponse;
import com.ledgerline.application.service.IdempotencyFastPath;
import com.ledgerline.domain.exception.IdempotencyKeyReusedException;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Redis-backed fast path for idempotency (ADR-015).
 * Absorbs duplicate requests and replay lookups in memory before transactions touch PostgreSQL.
 */
@Component
public class RedisIdempotencyFastPath implements IdempotencyFastPath {

    private static final Logger log = LoggerFactory.getLogger(RedisIdempotencyFastPath.class);

    private static final String COMPLETED_PREFIX = "idempotency:completed:";
    private static final String INFLIGHT_PREFIX = "idempotency:inflight:";

    private static final Lease BYPASSED_LEASE = new Lease() {
        @Override
        public boolean acquired() {
            return false;
        }

        @Override
        public void close() {
            // no-op
        }
    };

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;
    private final boolean enabled;
    private final Duration completedTtl;
    private final Duration inflightLeaseTtl;
    private final Duration pollInterval;

    public RedisIdempotencyFastPath(
            Optional<StringRedisTemplate> redisTemplate,
            Optional<ObjectMapper> objectMapper,
            MeterRegistry meterRegistry,
            @Value("${ledgerline.idempotency.redis.enabled:true}") boolean enabled,
            @Value("${ledgerline.idempotency.redis.completed-ttl:86400s}") Duration completedTtl,
            @Value("${ledgerline.idempotency.redis.inflight-lease-ttl:10s}") Duration inflightLeaseTtl,
            @Value("${ledgerline.idempotency.redis.poll-interval-ms:25ms}") Duration pollInterval) {
        this.redisTemplate = redisTemplate.orElse(null);
        this.objectMapper = objectMapper.orElseGet(() -> new ObjectMapper().findAndRegisterModules());
        this.meterRegistry = meterRegistry;
        this.enabled = enabled && this.redisTemplate != null;
        this.completedTtl = completedTtl;
        this.inflightLeaseTtl = inflightLeaseTtl;
        this.pollInterval = pollInterval;
    }

    @Override
    public Optional<TransferResponse> findCompleted(String clientId, String idempotencyKey, String requestHash) {
        if (!enabled) {
            return Optional.empty();
        }
        try {
            String key = completedKey(clientId, idempotencyKey);
            String json = redisTemplate.opsForValue().get(key);
            if (json == null) {
                return Optional.empty();
            }
            CachedPayload payload = objectMapper.readValue(json, CachedPayload.class);
            if (!Objects.equals(payload.requestHash(), requestHash)) {
                log.warn("Idempotency key reused with mismatched hash in fast path: client={}, key={}", clientId, idempotencyKey);
                throw new IdempotencyKeyReusedException(idempotencyKey);
            }
            meterRegistry.counter("ledger.idempotency.fastpath.hits").increment();
            return Optional.of(payload.response());
        } catch (IdempotencyKeyReusedException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Redis fast path read failed; falling back to database: {}", e.getMessage());
            meterRegistry.counter("ledger.idempotency.fastpath.fallback").increment();
            return Optional.empty();
        }
    }

    @Override
    public Lease acquireLease(String clientId, String idempotencyKey, String requestHash) {
        if (!enabled) {
            return BYPASSED_LEASE;
        }
        try {
            String key = inflightKey(clientId, idempotencyKey);
            Boolean success = redisTemplate.opsForValue().setIfAbsent(key, requestHash, inflightLeaseTtl);
            if (Boolean.TRUE.equals(success)) {
                return new RedisLease(clientId, idempotencyKey);
            }
            // Check if existing inflight key has mismatched hash
            String existingHash = redisTemplate.opsForValue().get(key);
            if (existingHash != null && !Objects.equals(existingHash, requestHash)) {
                log.warn("In-flight idempotency key conflict with mismatched hash: client={}, key={}", clientId, idempotencyKey);
                throw new IdempotencyKeyReusedException(idempotencyKey);
            }
            return BYPASSED_LEASE;
        } catch (IdempotencyKeyReusedException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Redis lease acquisition failed; falling back to database: {}", e.getMessage());
            meterRegistry.counter("ledger.idempotency.fastpath.fallback").increment();
            return BYPASSED_LEASE;
        }
    }

    @Override
    public Optional<TransferResponse> pollForCompleted(String clientId, String idempotencyKey, String requestHash, Duration timeout) {
        if (!enabled) {
            return Optional.empty();
        }
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        long sleepMs = Math.max(5, pollInterval.toMillis());
        while (System.currentTimeMillis() < deadline) {
            Optional<TransferResponse> completed = findCompleted(clientId, idempotencyKey, requestHash);
            if (completed.isPresent()) {
                meterRegistry.counter("ledger.idempotency.fastpath.inflight.absorbed").increment();
                return completed;
            }
            // Check if the inflight lease was released without completing (rolled back)
            try {
                Boolean inflightExists = redisTemplate.hasKey(inflightKey(clientId, idempotencyKey));
                if (Boolean.FALSE.equals(inflightExists)) {
                    // Check completed once more in case of race
                    return findCompleted(clientId, idempotencyKey, requestHash);
                }
            } catch (Exception e) {
                return Optional.empty();
            }
            try {
                Thread.sleep(sleepMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    @Override
    public void complete(String clientId, String idempotencyKey, String requestHash, TransferResponse response) {
        if (!enabled) {
            return;
        }
        try {
            String cKey = completedKey(clientId, idempotencyKey);
            String json = objectMapper.writeValueAsString(new CachedPayload(requestHash, response));
            redisTemplate.opsForValue().set(cKey, json, completedTtl);
            releaseLease(clientId, idempotencyKey);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize TransferResponse for fast path: {}", e.getMessage());
        } catch (Exception e) {
            log.warn("Redis complete cache write failed: {}", e.getMessage());
            meterRegistry.counter("ledger.idempotency.fastpath.fallback").increment();
        }
    }

    @Override
    public void releaseLease(String clientId, String idempotencyKey) {
        if (!enabled) {
            return;
        }
        try {
            redisTemplate.delete(inflightKey(clientId, idempotencyKey));
        } catch (Exception e) {
            log.warn("Redis lease release failed: {}", e.getMessage());
        }
    }

    private String completedKey(String clientId, String key) {
        return COMPLETED_PREFIX + clientId + ":" + key;
    }

    private String inflightKey(String clientId, String key) {
        return INFLIGHT_PREFIX + clientId + ":" + key;
    }

    public record CachedPayload(String requestHash, TransferResponse response) {}

    private final class RedisLease implements Lease {
        private final String clientId;
        private final String key;
        private final AtomicBoolean active = new AtomicBoolean(true);

        private RedisLease(String clientId, String key) {
            this.clientId = clientId;
            this.key = key;
        }

        @Override
        public boolean acquired() {
            return true;
        }

        @Override
        public void close() {
            if (active.compareAndSet(true, false)) {
                releaseLease(clientId, key);
            }
        }
    }
}
