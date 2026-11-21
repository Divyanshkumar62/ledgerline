package com.ledgerline.infrastructure.redis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.ledgerline.application.dto.TransferLegResponse;
import com.ledgerline.application.dto.TransferResponse;
import com.ledgerline.application.service.IdempotencyFastPath;
import com.ledgerline.domain.exception.IdempotencyKeyReusedException;
import com.ledgerline.domain.model.PostingDirection;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RedisIdempotencyFastPathTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOps;

    private ObjectMapper objectMapper;
    private SimpleMeterRegistry meterRegistry;
    private RedisIdempotencyFastPath fastPath;

    private final String client = "client-1";
    private final String key = "k-100";
    private final String hash = "hash-abc";

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        meterRegistry = new SimpleMeterRegistry();
        when(redisTemplate.opsForValue()).thenReturn(valueOps);

        fastPath = new RedisIdempotencyFastPath(
                Optional.of(redisTemplate),
                Optional.of(objectMapper),
                meterRegistry,
                true,
                Duration.ofHours(24),
                Duration.ofSeconds(10),
                Duration.ofMillis(20)
        );
    }

    private TransferResponse sampleResponse() {
        return new TransferResponse(
                UUID.randomUUID(),
                "test transfer",
                "USD",
                client + ":" + key,
                List.of(new TransferLegResponse(UUID.randomUUID(), UUID.randomUUID(), PostingDirection.DEBIT, 100L, "USD")),
                Instant.now()
        );
    }

    @Test
    @DisplayName("findCompleted returns cached response on cache hit with matching hash")
    void findCompleted_hit() throws Exception {
        TransferResponse response = sampleResponse();
        String json = objectMapper.writeValueAsString(new RedisIdempotencyFastPath.CachedPayload(hash, response));
        when(valueOps.get("idempotency:completed:client-1:k-100")).thenReturn(json);

        Optional<TransferResponse> result = fastPath.findCompleted(client, key, hash);

        assertThat(result).isPresent();
        assertThat(result.get().journalEntryId()).isEqualTo(response.journalEntryId());
        assertThat(meterRegistry.counter("ledger.idempotency.fastpath.hits").count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("findCompleted throws IdempotencyKeyReusedException on hash mismatch")
    void findCompleted_hashMismatch() throws Exception {
        TransferResponse response = sampleResponse();
        String json = objectMapper.writeValueAsString(new RedisIdempotencyFastPath.CachedPayload("different-hash", response));
        when(valueOps.get("idempotency:completed:client-1:k-100")).thenReturn(json);

        assertThatThrownBy(() -> fastPath.findCompleted(client, key, hash))
                .isInstanceOf(IdempotencyKeyReusedException.class);
    }

    @Test
    @DisplayName("findCompleted returns empty and increments fallback counter on Redis error")
    void findCompleted_redisError() {
        when(valueOps.get("idempotency:completed:client-1:k-100"))
                .thenThrow(new RedisConnectionFailureException("Connection refused"));

        Optional<TransferResponse> result = fastPath.findCompleted(client, key, hash);

        assertThat(result).isEmpty();
        assertThat(meterRegistry.counter("ledger.idempotency.fastpath.fallback").count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("acquireLease acquires in-flight lock when absent")
    void acquireLease_success() {
        when(valueOps.setIfAbsent(eq("idempotency:inflight:client-1:k-100"), eq(hash), any(Duration.class)))
                .thenReturn(true);

        IdempotencyFastPath.Lease lease = fastPath.acquireLease(client, key, hash);

        assertThat(lease.acquired()).isTrue();
        lease.close();
        verify(redisTemplate).delete("idempotency:inflight:client-1:k-100");
    }

    @Test
    @DisplayName("acquireLease throws IdempotencyKeyReusedException if lease held by different hash")
    void acquireLease_hashMismatch() {
        when(valueOps.setIfAbsent(eq("idempotency:inflight:client-1:k-100"), eq(hash), any(Duration.class)))
                .thenReturn(false);
        when(valueOps.get("idempotency:inflight:client-1:k-100")).thenReturn("another-hash");

        assertThatThrownBy(() -> fastPath.acquireLease(client, key, hash))
                .isInstanceOf(IdempotencyKeyReusedException.class);
    }

    @Test
    @DisplayName("acquireLease returns bypassed lease when lease held by same hash")
    void acquireLease_sameHashHeld() {
        when(valueOps.setIfAbsent(eq("idempotency:inflight:client-1:k-100"), eq(hash), any(Duration.class)))
                .thenReturn(false);
        when(valueOps.get("idempotency:inflight:client-1:k-100")).thenReturn(hash);

        IdempotencyFastPath.Lease lease = fastPath.acquireLease(client, key, hash);

        assertThat(lease.acquired()).isFalse();
        lease.close();
        verify(redisTemplate, never()).delete("idempotency:inflight:client-1:k-100");
    }

    @Test
    @DisplayName("complete writes cached payload and deletes in-flight lease")
    void complete_writesPayloadAndDeletesLease() {
        TransferResponse response = sampleResponse();

        fastPath.complete(client, key, hash, response);

        verify(valueOps).set(eq("idempotency:completed:client-1:k-100"), any(String.class), eq(Duration.ofHours(24)));
        verify(redisTemplate).delete("idempotency:inflight:client-1:k-100");
    }
}
