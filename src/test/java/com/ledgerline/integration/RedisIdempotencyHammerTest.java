package com.ledgerline.integration;

import com.ledgerline.application.dto.TransferOutcome;
import com.ledgerline.application.dto.TransferRequest;
import com.ledgerline.domain.model.Account;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.GenericContainer;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "ledgerline.idempotency.redis.enabled=true",
        "ledgerline.idempotency.redis.completed-ttl=86400s",
        "ledgerline.idempotency.redis.inflight-lease-ttl=10s",
        "ledgerline.idempotency.redis.poll-interval-ms=10ms"
})
class RedisIdempotencyHammerTest extends PostgresIntegrationTest {

    @ServiceConnection(name = "redis")
    static final GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379);

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("500 concurrent duplicates with Redis fast path → exactly 1 journal entry, 499 replays")
    void hammer_500DuplicatesAbsorbedWithRedis() throws Exception {
        String client = newClient().clientId();
        Account source = fundedAccount(1_000_000L, client);
        Account target = newAccount(client);
        TransferRequest request = transfer(source.getId(), target.getId(), 2_500L);
        Queue<TransferOutcome> outcomes = new ConcurrentLinkedQueue<>();

        Outcomes run = runConcurrently(POOL_SIZE, 500, i -> outcomes.add(transferService.executeIdempotent(client, "k-fast-1", request)));

        assertThat(run.failures()).isEmpty();
        assertThat(outcomes).hasSize(500);
        assertThat(outcomes).filteredOn(TransferOutcome::replayed).hasSize(499);
        assertThat(outcomes).extracting(o -> o.response().journalEntryId())
                .containsOnly(outcomes.peek().response().journalEntryId());

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM journal_entries WHERE idempotency_reference = ?", Integer.class, client + ":k-fast-1"))
                .isEqualTo(1);
        assertThat(balanceOf(source.getId())).isEqualTo(1_000_000L - 2_500L);
        assertThat(balanceOf(target.getId())).isEqualTo(2_500L);
        assertBalanceMatchesPostings(source.getId());
        assertBalanceMatchesPostings(target.getId());
    }

    @Test
    @DisplayName("Subsequent serial replays hit Redis fast path directly")
    void serialReplay_hitsFastPath() {
        String client = newClient().clientId();
        Account source = fundedAccount(50_000L, client);
        Account target = newAccount(client);
        TransferRequest request = transfer(source.getId(), target.getId(), 1_000L);

        TransferOutcome first = transferService.executeIdempotent(client, "k-serial", request);
        assertThat(first.replayed()).isFalse();

        TransferOutcome second = transferService.executeIdempotent(client, "k-serial", request);
        assertThat(second.replayed()).isTrue();
        assertThat(second.response().journalEntryId()).isEqualTo(first.response().journalEntryId());

        // Verify still exactly 1 journal entry
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM journal_entries WHERE idempotency_reference = ?", Integer.class, client + ":k-serial"))
                .isEqualTo(1);
    }
}
