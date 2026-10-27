package com.ledgerline.integration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** ADR-013: repair, re-audit, then unfreeze; every action recorded with who and why. */
class AccountAdminApiTest extends ApiTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("Postings are right: audit freezes → unfreeze refused → rebuild balance → audit clean → unfreeze → transfers work")
    void rebuildBalanceThenUnfreeze() throws Exception {
        Credentials client = newClient();
        UUID account = fundedAccountViaApi(client, "USD", 10_000);
        UUID other = createAccount(client, "ASSET", "USD", true);
        jdbc.update("UPDATE accounts SET balance = balance + 777 WHERE id = ?", account); // corrupt the cache

        postJson("/api/v1/admin/accounts/" + account + "/audit", ADMIN, null, "")
                .andExpect(status().isOk()).andExpect(jsonPath("$.newlyFrozen").value(true));
        postJson("/api/v1/transfers", client.bearer(), "k-" + UUID.randomUUID(), transferJson(account, other, 1, "USD"))
                .andExpect(status().isLocked());
        postJson("/api/v1/admin/accounts/" + account + "/unfreeze", ADMIN, null, reason("looks fine"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.discrepancy").value(777));

        postJson("/api/v1/admin/accounts/" + account + "/rebuild-balance", ADMIN, null, reason("INC-42: manual SQL edit"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balanceBefore").value(10_777))
                .andExpect(jsonPath("$.balanceAfter").value(10_000))
                .andExpect(jsonPath("$.frozen").value(true));
        postJson("/api/v1/admin/accounts/" + account + "/unfreeze", ADMIN, null, reason("INC-42 resolved"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.frozen").value(false));
        postJson("/api/v1/transfers", client.bearer(), "k-" + UUID.randomUUID(), transferJson(account, other, 1, "USD"))
                .andExpect(status().isCreated());

        assertThat(actions(account)).containsExactly("REBUILD_BALANCE", "UNFREEZE");
        assertThat(jdbc.queryForObject("SELECT reason FROM account_admin_actions WHERE account_id = ? AND action = 'REBUILD_BALANCE'",
                String.class, account)).isEqualTo("INC-42: manual SQL edit");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_events WHERE event_type = 'AccountAdminActionTaken' AND event_key = ?",
                Integer.class, account.toString())).isEqualTo(2);
        assertBalanceMatchesPostings(account);
    }

    @Test
    @DisplayName("Balance is right: book the missing posting against the adjustment account; cache unchanged, postings now agree")
    void bookMissingPostingThenUnfreeze() throws Exception {
        Credentials client = newClient();
        UUID account = fundedAccountViaApi(client, "USD", 10_000);
        jdbc.update("UPDATE accounts SET balance = balance + 500 WHERE id = ?", account); // real money whose posting was lost
        postJson("/api/v1/admin/accounts/" + account + "/audit", ADMIN, null, "").andExpect(status().isOk());

        MvcResult booked = postJson("/api/v1/admin/accounts/" + account + "/book-missing-posting", ADMIN, null, reason("Lost posting, bank stmt 2026-10-19"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balanceBefore").value(10_500))
                .andExpect(jsonPath("$.balanceAfter").value(10_500))
                .andExpect(jsonPath("$.journalEntryId").isNotEmpty())
                .andReturn();
        UUID entry = UUID.fromString(read(booked, "$.journalEntryId"));

        assertBalanceMatchesPostings(account);
        assertThat(jdbc.queryForObject("""
                SELECT a.system_purpose FROM postings p JOIN accounts a ON a.id = p.account_id
                 WHERE p.journal_entry_id = ? AND p.account_id <> ?
                """, String.class, entry, account)).isEqualTo("ADJUSTMENT");
        postJson("/api/v1/admin/accounts/" + account + "/book-missing-posting", ADMIN, null, reason("again"))
                .andExpect(status().isBadRequest()); // nothing left to book
        postJson("/api/v1/admin/accounts/" + account + "/unfreeze", ADMIN, null, reason("Reconciled"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.frozen").value(false));
    }

    @Test
    @DisplayName("Manual freeze blocks transfers both ways; a reason is mandatory; clients cannot use admin actions")
    void manualFreezeAndGuards() throws Exception {
        Credentials client = newClient();
        UUID account = fundedAccountViaApi(client, "USD", 1_000);
        UUID other = fundedAccountViaApi(client, "USD", 1_000);

        postJson("/api/v1/admin/accounts/" + account + "/freeze", ADMIN, null, reason(" "))
                .andExpect(status().isBadRequest());
        postJson("/api/v1/admin/accounts/" + account + "/freeze", client.bearer(), null, reason("self-freeze"))
                .andExpect(status().isForbidden());
        postJson("/api/v1/admin/accounts/" + account + "/freeze", ADMIN, null, reason("Suspected fraud, ticket F-7"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.frozen").value(true));

        postJson("/api/v1/transfers", client.bearer(), "k-" + UUID.randomUUID(), transferJson(account, other, 1, "USD"))
                .andExpect(status().isLocked());
        postJson("/api/v1/transfers", client.bearer(), "k-" + UUID.randomUUID(), transferJson(other, account, 1, "USD"))
                .andExpect(status().isLocked());

        postJson("/api/v1/admin/accounts/" + account + "/unfreeze", ADMIN, null, reason("Cleared by fraud team"))
                .andExpect(status().isOk());
        postJson("/api/v1/admin/accounts/" + account + "/unfreeze", ADMIN, null, reason("twice"))
                .andExpect(status().isBadRequest()); // not frozen
        assertThat(actions(account)).containsExactly("FREEZE", "UNFREEZE");
    }

    private List<String> actions(UUID account) {
        return jdbc.queryForList("SELECT action FROM account_admin_actions WHERE account_id = ? ORDER BY created_at", String.class, account);
    }
}
