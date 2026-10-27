package com.ledgerline.integration;

import com.ledgerline.application.dto.TransferOutcome;
import com.ledgerline.domain.model.Account;
import com.ledgerline.infrastructure.retention.RetentionJob;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** ADR-009: purge only what is safe to purge, and never ledger history. */
class RetentionJobTest extends PostgresIntegrationTest {

    private static final Duration RETENTION = Duration.ofDays(7);

    @Autowired
    private RetentionJob retention;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("Outbox: only events published longer ago than the retention are deleted; unpublished ones never")
    void outbox_keepsUnpublishedAndRecent() {
        Account source = fundedAccount(10_000L);
        Account target = newAccount();
        UUID oldPublished = transferService.executeTransfer(transfer(source.getId(), target.getId(), 1)).journalEntryId();
        UUID recentPublished = transferService.executeTransfer(transfer(source.getId(), target.getId(), 1)).journalEntryId();
        UUID oldUnpublished = transferService.executeTransfer(transfer(source.getId(), target.getId(), 1)).journalEntryId();
        jdbc.update("UPDATE outbox_events SET published_at = now() - interval '8 days' WHERE journal_entry_id = ?", oldPublished);
        jdbc.update("UPDATE outbox_events SET published_at = now() WHERE journal_entry_id = ?", recentPublished);
        jdbc.update("UPDATE outbox_events SET created_at = now() - interval '30 days' WHERE journal_entry_id = ?", oldUnpublished);

        assertThat(retention.purgePublishedOutbox(Instant.now().minus(RETENTION))).isGreaterThanOrEqualTo(1);

        assertThat(events(oldPublished)).isZero();
        assertThat(events(recentPublished)).isEqualTo(1);
        assertThat(events(oldUnpublished)).as("an unpublished event is never purged").isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM journal_entries WHERE id = ?", Integer.class, oldPublished))
                .as("ledger history is never purged").isEqualTo(1);
    }

    @Test
    @DisplayName("Idempotency: old keys are deleted, recent keys kept; a purged key executes again (why retention must be long)")
    void idempotency_purgesOldKeysOnly() {
        String client = newClient().clientId();
        Account source = fundedAccount(10_000L, client);
        Account target = newAccount(client);
        transferService.executeIdempotent(client, "old", transfer(source.getId(), target.getId(), 100));
        transferService.executeIdempotent(client, "recent", transfer(source.getId(), target.getId(), 100));
        jdbc.update("UPDATE idempotency_records SET created_at = now() - interval '8 days' WHERE client_id = ? AND idempotency_key = 'old'", client);

        assertThat(retention.purgeIdempotencyRecords(Instant.now().minus(RETENTION))).isGreaterThanOrEqualTo(1);

        assertThat(keys(client, "old")).isZero();
        assertThat(keys(client, "recent")).isEqualTo(1);
        assertThat(transferService.executeIdempotent(client, "recent", transfer(source.getId(), target.getId(), 100)).replayed()).isTrue();

        TransferOutcome afterPurge = transferService.executeIdempotent(client, "old", transfer(source.getId(), target.getId(), 100));
        assertThat(afterPurge.replayed()).as("purged key is no longer protected").isFalse();
        assertThat(balanceOf(target.getId())).isEqualTo(300L);
    }

    private int events(UUID journalEntryId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM outbox_events WHERE journal_entry_id = ?", Integer.class, journalEntryId);
    }

    private int keys(String client, String key) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_records WHERE client_id = ? AND idempotency_key = ?",
                Integer.class, client, key);
    }
}
