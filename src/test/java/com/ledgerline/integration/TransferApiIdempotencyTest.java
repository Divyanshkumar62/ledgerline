package com.ledgerline.integration;

import com.ledgerline.domain.model.Account;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The HTTP contract of POST /api/v1/transfers (feature.md §4). */
@AutoConfigureMockMvc
class TransferApiIdempotencyTest extends PostgresIntegrationTest {

    @Autowired
    private MockMvc mvc;

    private Credentials client;

    @BeforeEach
    void registerClient() {
        client = newClient();
    }

    @Test
    @DisplayName("Missing Idempotency-Key → 400; nothing executes")
    void missingKey_isBadRequest() throws Exception {
        Account source = fundedAccount(10_000L, client.clientId());
        Account target = newAccount(client.clientId());

        mvc.perform(post("/api/v1/transfers").header("Authorization", client.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content(body(source, target, 100)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Retry with the same key → same 201 body, marked X-Cache-Lookup: HIT-IDEMPOTENT")
    void retry_replaysOriginalResponse() throws Exception {
        Account source = fundedAccount(10_000L, client.clientId());
        Account target = newAccount(client.clientId());
        String key = UUID.randomUUID().toString();

        String firstBody = mvc.perform(transfer(key, body(source, target, 100)))
                .andExpect(status().isCreated())
                .andExpect(header().doesNotExist("X-Cache-Lookup"))
                .andReturn().getResponse().getContentAsString();

        mvc.perform(transfer(key, body(source, target, 100)))
                .andExpect(status().isCreated())
                .andExpect(header().string("X-Cache-Lookup", "HIT-IDEMPOTENT"))
                .andExpect(result -> assertThat(result.getResponse().getContentAsString()).isEqualTo(firstBody));
    }

    @Test
    @DisplayName("Same key, mutated body → 422 Unprocessable Entity")
    void sameKeyMutatedBody_isUnprocessable() throws Exception {
        Account source = fundedAccount(10_000L, client.clientId());
        Account target = newAccount(client.clientId());
        String key = UUID.randomUUID().toString();

        mvc.perform(transfer(key, body(source, target, 100))).andExpect(status().isCreated());

        mvc.perform(transfer(key, body(source, target, 999)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.title").value("Idempotency Key Reused"));
    }

    private MockHttpServletRequestBuilder transfer(String key, String json) {
        return post("/api/v1/transfers")
                .header("Authorization", client.bearer())
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json);
    }

    private static String body(Account from, Account to, long amount) {
        return """
                {"description":"api transfer","currency":"usd","legs":[
                  {"accountId":"%s","direction":"CREDIT","amountMinor":%d},
                  {"accountId":"%s","direction":"DEBIT","amountMinor":%d}]}
                """.formatted(from.getId(), amount, to.getId(), amount);
    }
}
