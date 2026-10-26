package com.ledgerline.integration;

import com.jayway.jsonpath.JsonPath;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** HTTP-level tests: everything goes through the real security chain, controllers and error mapping. */
@AutoConfigureMockMvc
abstract class ApiTest extends PostgresIntegrationTest {

    @Autowired
    protected MockMvc mvc;

    protected static final String ADMIN = "Bearer " + ADMIN_KEY;

    protected ResultActions postJson(String path, String bearer, String idempotencyKey, String json) throws Exception {
        MockHttpServletRequestBuilder request = post(path).contentType(MediaType.APPLICATION_JSON).content(json);
        if (bearer != null) {
            request.header("Authorization", bearer);
        }
        if (idempotencyKey != null) {
            request.header("Idempotency-Key", idempotencyKey);
        }
        return mvc.perform(request);
    }

    protected ResultActions getAs(String path, String bearer) throws Exception {
        return mvc.perform(get(path).header("Authorization", bearer));
    }

    protected UUID createAccount(Credentials owner, String type, String currency, boolean noOverdraft) throws Exception {
        MvcResult result = postJson("/api/v1/accounts", owner.bearer(), null,
                "{\"type\":\"%s\",\"currency\":\"%s\",\"disallowNegativeBalance\":%s}".formatted(type, currency, noOverdraft))
                .andExpect(status().isCreated()).andReturn();
        return UUID.fromString(read(result, "$.id"));
    }

    /** An asset account funded from the owner's own capital (equity) account, entirely over the API. */
    protected UUID fundedAccountViaApi(Credentials owner, String currency, long amount) throws Exception {
        UUID capital = createAccount(owner, "EQUITY", currency, false);
        UUID asset = createAccount(owner, "ASSET", currency, true);
        postJson("/api/v1/transfers", owner.bearer(), "fund-" + UUID.randomUUID(), transferJson(capital, asset, amount, currency))
                .andExpect(status().isCreated());
        return asset;
    }

    protected static String transferJson(UUID from, UUID to, long amount, String currency) {
        return """
                {"description":"api transfer","currency":"%s","legs":[
                  {"accountId":"%s","direction":"CREDIT","amountMinor":%d},
                  {"accountId":"%s","direction":"DEBIT","amountMinor":%d}]}
                """.formatted(currency, from, amount, to, amount);
    }

    protected static String reason(String text) {
        return "{\"reason\":\"%s\"}".formatted(text);
    }

    protected static <T> T read(MvcResult result, String jsonPath) throws Exception {
        return JsonPath.read(result.getResponse().getContentAsString(), jsonPath);
    }
}
