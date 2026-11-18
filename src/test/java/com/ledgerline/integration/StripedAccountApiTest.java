package com.ledgerline.integration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** ADR-014 over HTTP: creating a striped account, paying into it, reading it back, and the rejections. */
class StripedAccountApiTest extends ApiTest {

    private UUID createStriped(Credentials owner, int stripes) throws Exception {
        MvcResult result = postJson("/api/v1/accounts", owner.bearer(), null,
                "{\"type\":\"ASSET\",\"currency\":\"USD\",\"disallowNegativeBalance\":false,\"stripeCount\":%d}".formatted(stripes))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.stripeCount").value(stripes))
                .andExpect(jsonPath("$.balance").value(0))
                .andReturn();
        return UUID.fromString(read(result, "$.id"));
    }

    @Test
    @DisplayName("Credits spread over stripes; GET shows the summed balance; the statement merges stripes newest-first")
    void payIntoStripedAccountAndReadItBack() throws Exception {
        Credentials client = newClient();
        UUID source = fundedAccountViaApi(client, "USD", 10_000);
        UUID sink = createStriped(client, 4);

        for (int amount = 100; amount <= 600; amount += 100) {
            postJson("/api/v1/transfers", client.bearer(), "fee-" + UUID.randomUUID(), transferJson(source, sink, amount, "USD"))
                    .andExpect(status().isCreated());
        }
        // 100 + 200 + ... + 600 = 2100

        getAs("/api/v1/accounts/" + sink, client.bearer())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(2_100))
                .andExpect(jsonPath("$.stripeCount").value(4));

        MvcResult page = getAs("/api/v1/accounts/" + sink + "/postings?limit=50", client.bearer())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(2_100))
                .andReturn();
        List<Map<String, Object>> lines = read(page, "$.postings");
        assertThat(lines).extracting(l -> ((Number) l.get("signedAmountMinor")).longValue())
                .containsExactly(600L, 500L, 400L, 300L, 200L, 100L);
        assertThat(((Number) lines.get(0).get("balanceAfterMinor")).longValue()).isEqualTo(2_100L);
        assertThat(((Number) lines.get(5).get("balanceAfterMinor")).longValue()).isEqualTo(100L);
    }

    @Test
    @DisplayName("Paying OUT of a striped account → 400 (receive-only); naming a stripe directly → 400; a stripe shows its parent")
    void receiveOnlyRulesAreEnforced() throws Exception {
        Credentials client = newClient();
        UUID source = fundedAccountViaApi(client, "USD", 10_000);
        UUID sink = createStriped(client, 4);
        MvcResult paid = postJson("/api/v1/transfers", client.bearer(), "fee-" + UUID.randomUUID(), transferJson(source, sink, 500, "USD"))
                .andExpect(status().isCreated()).andReturn();
        UUID stripe = UUID.fromString(read(paid, "$.legs[1].accountId"));

        postJson("/api/v1/transfers", client.bearer(), "out-" + UUID.randomUUID(), transferJson(sink, source, 100, "USD"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("receive-only")));
        postJson("/api/v1/transfers", client.bearer(), "direct-" + UUID.randomUUID(), transferJson(source, stripe, 100, "USD"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("is a stripe of account " + sink)));

        getAs("/api/v1/accounts/" + stripe, client.bearer())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.parentAccountId").value(sink.toString()))
                .andExpect(jsonPath("$.balance").value(500));
        getAs("/api/v1/accounts/" + sink, client.bearer()).andExpect(jsonPath("$.balance").value(500));
    }

    @Test
    @DisplayName("Striping an account with a balance check → 400; stripeCount outside 0..64 → 400")
    void creationIsValidated() throws Exception {
        Credentials client = newClient();
        postJson("/api/v1/accounts", client.bearer(), null,
                "{\"type\":\"ASSET\",\"currency\":\"USD\",\"disallowNegativeBalance\":true,\"stripeCount\":4}")
                .andExpect(status().isBadRequest());
        postJson("/api/v1/accounts", client.bearer(), null,
                "{\"type\":\"ASSET\",\"currency\":\"USD\",\"disallowNegativeBalance\":false,\"stripeCount\":65}")
                .andExpect(status().isBadRequest());
        postJson("/api/v1/accounts", client.bearer(), null,
                "{\"type\":\"ASSET\",\"currency\":\"USD\",\"disallowNegativeBalance\":false,\"stripeCount\":-1}")
                .andExpect(status().isBadRequest());
    }
}
