package com.ledgerline.integration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** ADR-012: cross-currency transfers balanced per currency through FX clearing accounts. */
class FxTransferApiTest extends ApiTest {

    @Autowired
    private JdbcTemplate jdbc;

    private static String fx(UUID from, UUID to, long amount, String rate) {
        return """
                {"description":"USD to EUR","fromAccountId":"%s","toAccountId":"%s","sourceAmountMinor":%d,"rate":%s}
                """.formatted(from, to, amount, rate);
    }

    @Test
    @DisplayName("100.00 USD at 0.9150 → 91.50 EUR: four legs, zero-sum per currency, clearing accounts hold the position")
    void convertsThroughClearingAccounts() throws Exception {
        Credentials client = newClient();
        UUID usd = fundedAccountViaApi(client, "USD", 10_000);
        UUID eur = createAccount(client, "ASSET", "EUR", true);
        long usdClearingBefore = clearingBalance("USD");
        long eurClearingBefore = clearingBalance("EUR");

        MvcResult result = postJson("/api/v1/fx-transfers", client.bearer(), "fx-" + UUID.randomUUID(), fx(usd, eur, 10_000, "0.9150"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sourceCurrency").value("USD"))
                .andExpect(jsonPath("$.targetCurrency").value("EUR"))
                .andExpect(jsonPath("$.sourceAmountMinor").value(10_000))
                .andExpect(jsonPath("$.targetAmountMinor").value(9_150))
                .andExpect(jsonPath("$.roundingMode").value("HALF_EVEN"))
                .andExpect(jsonPath("$.transfer.legs.length()").value(4))
                .andReturn();
        UUID entry = UUID.fromString(read(result, "$.transfer.journalEntryId"));

        assertThat(balanceOf(usd)).isZero();
        assertThat(balanceOf(eur)).isEqualTo(9_150L);
        assertThat(clearingBalance("USD") - usdClearingBefore).as("ledger received USD").isEqualTo(10_000L);
        assertThat(clearingBalance("EUR") - eurClearingBefore).as("ledger paid out EUR").isEqualTo(-9_150L);
        assertThat(jdbc.queryForObject("SELECT currency FROM journal_entries WHERE id = ?", String.class, entry))
                .as("an FX entry has no single currency").isNull();

        List<Map<String, Object>> perCurrency = jdbc.queryForList("""
                SELECT currency, SUM(CASE WHEN direction = 'DEBIT' THEN amount ELSE -amount END) AS net
                  FROM postings WHERE journal_entry_id = ? GROUP BY currency ORDER BY currency
                """, entry);
        assertThat(perCurrency).extracting(r -> r.get("currency"), r -> ((Number) r.get("net")).longValue())
                .containsExactly(org.assertj.core.groups.Tuple.tuple("EUR", 0L), org.assertj.core.groups.Tuple.tuple("USD", 0L));
        assertBalanceMatchesPostings(usd);
        assertBalanceMatchesPostings(eur);
    }

    @Test
    @DisplayName("Idempotent like any transfer: replay returns the same conversion; a different rate under the same key → 422")
    void idempotentReplayAndKeyReuse() throws Exception {
        Credentials client = newClient();
        UUID usd = fundedAccountViaApi(client, "USD", 10_000);
        UUID jpy = createAccount(client, "ASSET", "JPY", true);
        String key = "fx-" + UUID.randomUUID();

        String first = postJson("/api/v1/fx-transfers", client.bearer(), key, fx(usd, jpy, 1_234, "151.237"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.targetAmountMinor").value(1_866)) // 12.34 USD → 1866 JPY (no minor unit)
                .andReturn().getResponse().getContentAsString();
        postJson("/api/v1/fx-transfers", client.bearer(), key, fx(usd, jpy, 1_234, "151.237"))
                .andExpect(status().isCreated())
                .andExpect(header().string("X-Cache-Lookup", "HIT-IDEMPOTENT"))
                .andExpect(r -> assertThat(r.getResponse().getContentAsString()).isEqualTo(first));
        postJson("/api/v1/fx-transfers", client.bearer(), key, fx(usd, jpy, 1_234, "150"))
                .andExpect(status().isUnprocessableContent());

        assertThat(balanceOf(jpy)).isEqualTo(1_866L);
    }

    @Test
    @DisplayName("Rejected: same currency (400), overdraft (422, nothing moves), someone else's account (404)")
    void rejections() throws Exception {
        Credentials client = newClient();
        UUID usd = fundedAccountViaApi(client, "USD", 1_000);
        UUID otherUsd = createAccount(client, "ASSET", "USD", true);
        UUID eur = createAccount(client, "ASSET", "EUR", true);
        UUID strangersEur = createAccount(newClient(), "ASSET", "EUR", true);

        postJson("/api/v1/fx-transfers", client.bearer(), "fx-" + UUID.randomUUID(), fx(usd, otherUsd, 100, "1"))
                .andExpect(status().isBadRequest());
        postJson("/api/v1/fx-transfers", client.bearer(), "fx-" + UUID.randomUUID(), fx(usd, eur, 5_000, "0.9"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.title").value("Insufficient Funds"));
        postJson("/api/v1/fx-transfers", client.bearer(), "fx-" + UUID.randomUUID(), fx(usd, strangersEur, 100, "0.9"))
                .andExpect(status().isNotFound());

        assertThat(balanceOf(usd)).isEqualTo(1_000L);
        assertThat(balanceOf(eur)).isZero();
    }

    private long clearingBalance(String currency) {
        List<Long> balances = jdbc.queryForList(
                "SELECT balance FROM accounts WHERE system_purpose = 'FX_CLEARING' AND currency = ?", Long.class, currency);
        return balances.isEmpty() ? 0 : balances.get(0);
    }
}
