package com.ledgerline.integration;

import com.ledgerline.application.dto.TransferResponse;
import com.ledgerline.domain.exception.InsufficientFundsException;
import com.ledgerline.domain.model.Account;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Week 5: the event exists if and only if the ledger change committed. */
class OutboxAtomicityTest extends PostgresIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("A committed transfer writes exactly one TransferCompleted event in the same transaction")
    void committedTransfer_writesOneEvent() {
        Account source = fundedAccount(5_000L);
        Account target = newAccount();

        TransferResponse response = transferService.executeTransfer(transfer(source.getId(), target.getId(), 1_200L));

        assertThat(eventsFor(response.journalEntryId())).isEqualTo(1);
        String payload = jdbcTemplate.queryForObject(
                "SELECT payload FROM outbox_events WHERE journal_entry_id = ?", String.class, response.journalEntryId());
        assertThat(payload).contains(response.journalEntryId().toString(), "\"amountMinor\":1200", "\"currency\":\"USD\"");
        assertThat(jdbcTemplate.queryForObject("SELECT topic FROM outbox_events WHERE journal_entry_id = ?",
                String.class, response.journalEntryId())).isEqualTo("ledger.transfers.v1");
    }

    @Test
    @DisplayName("A failed transfer leaves no event; an idempotent replay adds no event")
    void failedTransferAndReplay_writeNoEvent() {
        Account empty = newAccount();
        Account target = newAccount();
        int before = totalEvents();

        assertThatThrownBy(() -> transferService.executeTransfer(transfer(empty.getId(), target.getId(), 100L)))
                .isInstanceOf(InsufficientFundsException.class);
        assertThat(totalEvents()).isEqualTo(before);

        String client = newClient().clientId();
        Account source = fundedAccount(1_000L, client);
        Account ownTarget = newAccount(client);
        UUID entry = transferService.executeIdempotent(client, "k", transfer(source.getId(), ownTarget.getId(), 100L))
                .response().journalEntryId();
        transferService.executeIdempotent(client, "k", transfer(source.getId(), ownTarget.getId(), 100L)); // replay

        assertThat(eventsFor(entry)).isEqualTo(1);
    }

    private int eventsFor(UUID journalEntryId) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM outbox_events WHERE journal_entry_id = ?", Integer.class, journalEntryId);
    }

    private int totalEvents() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM outbox_events", Integer.class);
    }
}
