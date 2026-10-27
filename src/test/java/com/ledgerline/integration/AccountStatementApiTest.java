package com.ledgerline.integration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Account statement: newest first, keyset pagination, and a correct running balance on every line. */
class AccountStatementApiTest extends ApiTest {

    @Test
    @DisplayName("Paginates newest-first with a cursor; balanceAfter chains exactly down to the first posting")
    void statementPaginatesWithRunningBalance() throws Exception {
        Credentials client = newClient();
        UUID account = fundedAccountViaApi(client, "USD", 10_000);
        UUID other = createAccount(client, "ASSET", "USD", true);
        for (int amount = 100; amount <= 500; amount += 100) {
            postJson("/api/v1/transfers", client.bearer(), "pay-" + UUID.randomUUID(), transferJson(account, other, amount, "USD"))
                    .andExpect(status().isCreated());
        }
        // 6 postings on the account: +10000 funding, then -100 -200 -300 -400 -500 → balance 8500

        List<Map<String, Object>> lines = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        do {
            MvcResult page = getAs("/api/v1/accounts/" + account + "/postings?limit=4" + (cursor == null ? "" : "&before=" + cursor), client.bearer())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.currency").value("USD"))
                    .andExpect(jsonPath("$.balance").value(8_500))
                    .andReturn();
            lines.addAll(read(page, "$.postings"));
            Object next = read(page, "$.nextCursor");
            cursor = next == null ? null : next.toString();
            pages++;
        } while (cursor != null);

        assertThat(pages).isEqualTo(2);
        assertThat(lines).extracting(l -> ((Number) l.get("signedAmountMinor")).longValue())
                .containsExactly(-500L, -400L, -300L, -200L, -100L, 10_000L);
        assertThat(((Number) lines.get(0).get("balanceAfterMinor")).longValue()).as("newest line = current balance").isEqualTo(8_500L);
        for (int i = 1; i < lines.size(); i++) {
            long newer = ((Number) lines.get(i - 1).get("balanceAfterMinor")).longValue();
            long newerSigned = ((Number) lines.get(i - 1).get("signedAmountMinor")).longValue();
            assertThat(((Number) lines.get(i).get("balanceAfterMinor")).longValue()).isEqualTo(newer - newerSigned);
        }
        assertThat(((Number) lines.get(lines.size() - 1).get("balanceAfterMinor")).longValue()).as("oldest line").isEqualTo(10_000L);
        assertThat(lines.get(1).get("description")).isEqualTo("api transfer");
    }

    @Test
    @DisplayName("limit outside 1..200 → 400")
    void limitIsValidated() throws Exception {
        Credentials client = newClient();
        UUID account = createAccount(client, "ASSET", "USD", true);
        getAs("/api/v1/accounts/" + account + "/postings?limit=0", client.bearer()).andExpect(status().isBadRequest());
        getAs("/api/v1/accounts/" + account + "/postings?limit=201", client.bearer()).andExpect(status().isBadRequest());
        getAs("/api/v1/accounts/" + account + "/postings", client.bearer())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.postings.length()").value(0))
                .andExpect(jsonPath("$.nextCursor").isEmpty());
    }
}
