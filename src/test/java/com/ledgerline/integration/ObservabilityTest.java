package com.ledgerline.integration;

import com.ledgerline.domain.model.Account;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Week 7: correlation fields in logs, trace id propagation, and the business metrics. */
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class ObservabilityTest extends PostgresIntegrationTest {

    @Autowired
    private MockMvc mvc;

    private Credentials client;

    @BeforeEach
    void registerClient() {
        client = newClient();
    }

    @Autowired
    private MeterRegistry meters;

    @Test
    @DisplayName("Every log line of a transfer carries trace_id, idempotency_key, source and target account")
    void logLinesCarryCorrelationFields(CapturedOutput output) throws Exception {
        Account source = fundedAccount(10_000L, client.clientId());
        Account target = newAccount(client.clientId());
        String key = "obs-" + UUID.randomUUID();

        mvc.perform(transfer(key, body(source, target, 100)).header("X-Request-Id", "it-trace-42"))
                .andExpect(status().isCreated())
                .andExpect(header().string("X-Trace-Id", "it-trace-42"));

        String line = output.getOut().lines().filter(l -> l.contains("Transfer committed")).reduce((a, b) -> b).orElseThrow();
        assertThat(line).contains("trace_id=it-trace-42", "client_id=" + client.clientId(), "idempotency_key=" + key,
                "source_account_id=" + source.getId(), "target_account_id=" + target.getId());
    }

    @Test
    @DisplayName("An unsafe caller-supplied request id is replaced, so clients cannot inject text into logs")
    void unsafeRequestIdIsReplaced() throws Exception {
        Account source = fundedAccount(1_000L, client.clientId());
        Account target = newAccount(client.clientId());

        String traceId = mvc.perform(transfer(UUID.randomUUID().toString(), body(source, target, 1))
                        .header("X-Request-Id", "x".repeat(65)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getHeader("X-Trace-Id");

        assertThat(traceId).isNotEqualTo("x".repeat(65));
        assertThat(UUID.fromString(traceId)).isNotNull();
    }

    @Test
    @DisplayName("Idempotency hits / misses / mismatches and the transfer timer are recorded")
    void businessMetricsAreRecorded() throws Exception {
        Account source = fundedAccount(10_000L, client.clientId());
        Account target = newAccount(client.clientId());
        String key = "metrics-" + UUID.randomUUID();
        double hits = count("ledger.idempotency.hits");
        double misses = count("ledger.idempotency.misses");
        double mismatches = count("ledger.idempotency.mismatches");
        long committed = timerCount("committed");
        long replayed = timerCount("replayed");
        long rejected = timerCount("rejected");

        mvc.perform(transfer(key, body(source, target, 100))).andExpect(status().isCreated());        // miss
        mvc.perform(transfer(key, body(source, target, 100))).andExpect(status().isCreated());        // hit
        mvc.perform(transfer(key, body(source, target, 999))).andExpect(status().isUnprocessableContent()); // mismatch

        assertThat(count("ledger.idempotency.misses")).isEqualTo(misses + 1);
        assertThat(count("ledger.idempotency.hits")).isEqualTo(hits + 1);
        assertThat(count("ledger.idempotency.mismatches")).isEqualTo(mismatches + 1);
        assertThat(timerCount("committed")).isGreaterThanOrEqualTo(committed + 1);
        assertThat(timerCount("replayed")).isEqualTo(replayed + 1);
        assertThat(timerCount("rejected")).isEqualTo(rejected + 1);
        assertThat(meters.find("ledger.outbox.backlog").gauge()).as("outbox gauge registered").isNotNull();
        assertThat(meters.find("ledger.outbox.backlog").gauge().value()).isGreaterThanOrEqualTo(0);
    }

    private double count(String name) {
        Counter counter = meters.find(name).counter();
        return counter == null ? 0 : counter.count();
    }

    private long timerCount(String outcome) {
        Timer timer = meters.find("ledger.transfer.duration").tag("strategy", "ORDERED_PESSIMISTIC").tag("outcome", outcome).timer();
        return timer == null ? 0 : timer.count();
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
                {"description":"observability","currency":"USD","legs":[
                  {"accountId":"%s","direction":"CREDIT","amountMinor":%d},
                  {"accountId":"%s","direction":"DEBIT","amountMinor":%d}]}
                """.formatted(from.getId(), amount, to.getId(), amount);
    }
}
