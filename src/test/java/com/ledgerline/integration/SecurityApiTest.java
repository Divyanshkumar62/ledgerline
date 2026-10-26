package com.ledgerline.integration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** ADR-011: API-key authentication, roles, and owner-only access to accounts. */
class SecurityApiTest extends ApiTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("No key or an unknown key → 401 problem+json with WWW-Authenticate: Bearer")
    void unauthenticatedRequestsAreRejected() throws Exception {
        postJson("/api/v1/accounts", null, null, "{\"type\":\"ASSET\",\"currency\":\"USD\",\"disallowNegativeBalance\":true}")
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.title").value("Unauthorized"));
        getAs("/api/v1/accounts/" + UUID.randomUUID(), "Bearer ll_not-a-real-key").andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Health is public; a client key cannot reach admin endpoints (403)")
    void rolesAreEnforced() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
        Credentials client = newClient();
        postJson("/api/v1/admin/clients", client.bearer(), null, "{\"clientId\":\"x-sneaky\",\"displayName\":\"x\",\"role\":\"ADMIN\"}")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.title").value("Forbidden"));
    }

    @Test
    @DisplayName("Admin creates a client; the returned key works once shown and is never stored in plaintext")
    void adminCreatesClient() throws Exception {
        String id = "shop-" + UUID.randomUUID().toString().substring(0, 8);
        MvcResult created = postJson("/api/v1/admin/clients", ADMIN, null,
                "{\"clientId\":\"%s\",\"displayName\":\"Shop\",\"role\":\"CLIENT\"}".formatted(id))
                .andExpect(status().isCreated()).andReturn();
        String key = read(created, "$.apiKey");

        assertThat(key).startsWith("ll_").hasSizeGreaterThan(40);
        assertThat(jdbc.queryForObject("SELECT key_hash FROM api_clients WHERE client_id = ?", String.class, id))
                .hasSize(64).isNotEqualTo(key);
        postJson("/api/v1/accounts", "Bearer " + key, null, "{\"type\":\"ASSET\",\"currency\":\"USD\",\"disallowNegativeBalance\":true}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.ownerClientId").value(id));
    }

    @Test
    @DisplayName("A deactivated client's key stops working immediately")
    void deactivatedClientIsRejected() throws Exception {
        Credentials client = newClient();
        createAccount(client, "ASSET", "USD", true);
        jdbc.update("UPDATE api_clients SET active = FALSE WHERE client_id = ?", client.clientId());
        postJson("/api/v1/accounts", client.bearer(), null, "{\"type\":\"ASSET\",\"currency\":\"USD\",\"disallowNegativeBalance\":true}")
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Another client's account is invisible (404) and cannot be moved; nothing changes, no key is consumed")
    void accountsAreOwnerOnly() throws Exception {
        Credentials alice = newClient();
        Credentials mallory = newClient();
        UUID aliceAccount = fundedAccountViaApi(alice, "USD", 10_000);
        UUID malloryAccount = createAccount(mallory, "ASSET", "USD", true);

        getAs("/api/v1/accounts/" + aliceAccount, mallory.bearer()).andExpect(status().isNotFound());
        getAs("/api/v1/accounts/" + aliceAccount + "/postings", mallory.bearer()).andExpect(status().isNotFound());
        postJson("/api/v1/transfers", mallory.bearer(), "steal-1", transferJson(aliceAccount, malloryAccount, 10_000, "USD"))
                .andExpect(status().isNotFound());

        assertThat(balanceOf(aliceAccount)).isEqualTo(10_000L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_records WHERE client_id = ?", Integer.class,
                mallory.clientId())).isZero();
        getAs("/api/v1/accounts/" + aliceAccount, ADMIN).andExpect(status().isOk()); // admins can see every account
    }

    @Test
    @DisplayName("Unknown currency codes are rejected when an account is opened")
    void unknownCurrencyIsRejected() throws Exception {
        postJson("/api/v1/accounts", newClient().bearer(), null, "{\"type\":\"ASSET\",\"currency\":\"XYZ\",\"disallowNegativeBalance\":true}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Unknown ISO 4217 currency: XYZ"));
    }
}
