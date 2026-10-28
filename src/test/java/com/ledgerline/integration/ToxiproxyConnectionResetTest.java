package com.ledgerline.integration;

import com.ledgerline.application.dto.TransferLegRequest;
import com.ledgerline.application.dto.TransferOutcome;
import com.ledgerline.application.dto.TransferRequest;
import com.ledgerline.application.service.ApiClientService;
import com.ledgerline.application.service.LedgerAuditService;
import com.ledgerline.application.service.TransferService;
import com.ledgerline.domain.model.Account;
import com.ledgerline.domain.model.AccountType;
import com.ledgerline.domain.model.ApiClient;
import com.ledgerline.domain.model.PostingDirection;
import com.ledgerline.domain.repository.AccountRepository;
import eu.rekawek.toxiproxy.Proxy;
import eu.rekawek.toxiproxy.ToxiproxyClient;
import eu.rekawek.toxiproxy.model.ToxicDirection;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.Network;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.toxiproxy.ToxiproxyContainer;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Week 6 chaos proof: TCP connections to PostgreSQL are reset mid-transaction while transfers run.
 *
 * A reset during COMMIT is the nasty case: the client gets an exception but cannot know whether the
 * transaction committed. The answer is idempotency: every client retries its failed requests with the
 * SAME key, and each one either replays (it had committed) or executes (it had not).
 *
 * Own PostgreSQL on a Docker network so the app's datasource can be routed through Toxiproxy.
 */
@SpringBootTest(properties = {
        "spring.datasource.hikari.maximum-pool-size=16",
        "spring.datasource.hikari.connection-timeout=5000",
        "ledgerline.outbox.relay.enabled=false",
        "ledgerline.audit.enabled=false",
        "ledgerline.retention.cron=-"
})
class ToxiproxyConnectionResetTest {

    private static final int WORKERS = 12;
    private static final String CLIENT = "chaos-client";

    static final Network network = Network.newNetwork();
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16")
            .withNetwork(network).withNetworkAliases("pg");
    static final ToxiproxyContainer toxiproxy = new ToxiproxyContainer("ghcr.io/shopify/toxiproxy:2.5.0")
            .withNetwork(network);
    static final Proxy proxy;

    static {
        postgres.start();
        toxiproxy.start();
        try {
            proxy = new ToxiproxyClient(toxiproxy.getHost(), toxiproxy.getControlPort())
                    .createProxy("postgres", "0.0.0.0:8666", "pg:5432");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void datasourceThroughProxy(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:postgresql://%s:%d/%s".formatted(
                toxiproxy.getHost(), toxiproxy.getMappedPort(8666), postgres.getDatabaseName()));
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private TransferService transferService;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private LedgerAuditService audit;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ApiClientService apiClients;

    @Test
    @DisplayName("Connection resets under load: no partial commit, one effect per key after retries, zero audit drift")
    void connectionResetUnderLoad() throws Exception {
        apiClients.create(CLIENT, "chaos client", ApiClient.Role.CLIENT);
        Account capital = accountRepository.save(new Account(AccountType.EQUITY, "USD", false));
        List<UUID> accounts = IntStream.range(0, 4).mapToObj(i -> {
            Account a = accountRepository.save(new Account(AccountType.ASSET, "USD", true, CLIENT));
            transferService.executeTransfer(transfer(capital.getId(), a.getId(), 1_000_000L));
            return a.getId();
        }).toList();

        Map<String, TransferRequest> issued = new ConcurrentHashMap<>();
        Map<String, Throwable> failed = new ConcurrentHashMap<>();
        AtomicInteger next = new AtomicInteger();
        AtomicBoolean running = new AtomicBoolean(true);

        ExecutorService pool = Executors.newFixedThreadPool(WORKERS);
        for (int w = 0; w < WORKERS; w++) {
            pool.submit(() -> {
                while (running.get()) {
                    int n = next.getAndIncrement();
                    String key = "k-" + n;
                    SplittableRandom rnd = new SplittableRandom(n);
                    int from = rnd.nextInt(accounts.size());
                    int to = (from + 1 + rnd.nextInt(accounts.size() - 1)) % accounts.size();
                    TransferRequest request = transfer(accounts.get(from), accounts.get(to), 1 + rnd.nextInt(100));
                    issued.put(key, request);
                    try {
                        transferService.executeIdempotent(CLIENT, key, request);
                    } catch (RuntimeException e) {
                        failed.put(key, e);
                    }
                }
            });
        }

        // Chaos window: TCP RST on the server -> client direction. A COMMIT that reached the server is applied,
        // but its reply is lost: the client sees an error for a transfer that DID commit (the ambiguous case).
        Thread.sleep(1_500);
        var reset = proxy.toxics().resetPeer("reset", ToxicDirection.DOWNSTREAM, 0);
        Thread.sleep(1_500);
        reset.remove();
        Thread.sleep(1_500);
        running.set(false);
        pool.shutdown();
        assertThat(pool.awaitTermination(1, TimeUnit.MINUTES)).isTrue();

        assertThat(failed).as("the chaos window must actually break some requests").isNotEmpty();

        // Clients retry every failed request with the SAME key until it goes through.
        int committedDespiteError = 0;
        for (String key : failed.keySet()) {
            TransferOutcome outcome = retryUntilAnswered(key, issued.get(key));
            if (outcome.replayed()) {
                committedDespiteError++; // the "failed" request had in fact committed
            }
        }
        System.out.printf("chaos: issued=%d failedDuringChaos=%d committedDespiteError=%d%n",
                issued.size(), failed.size(), committedDespiteError);

        // Exactly one ledger effect per key, nothing for keys that were never issued.
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM journal_entries WHERE idempotency_reference LIKE ?",
                Integer.class, CLIENT + ":%")).isEqualTo(issued.size());
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM (
                    SELECT idempotency_reference FROM journal_entries
                     WHERE idempotency_reference LIKE ? GROUP BY idempotency_reference HAVING COUNT(*) > 1) dup
                """, Integer.class, CLIENT + ":%")).isZero();

        // No partial commit anywhere: every journal entry has >= 2 legs and nets to zero.
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM (
                    SELECT journal_entry_id FROM postings GROUP BY journal_entry_id
                    HAVING COUNT(*) < 2 OR SUM(CASE WHEN direction = 'DEBIT' THEN amount ELSE -amount END) <> 0) bad
                """, Integer.class)).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM journal_entries je
                 WHERE NOT EXISTS (SELECT 1 FROM postings p WHERE p.journal_entry_id = je.id)
                """, Integer.class)).isZero();

        // Money is conserved and the audit finds zero discrepancies.
        assertThat(accounts.stream().mapToLong(id -> accountRepository.findById(id).orElseThrow().getBalance()).sum())
                .isEqualTo(4_000_000L);
        List<UUID> all = new ArrayList<>(accounts);
        all.add(capital.getId());
        assertThat(audit.audit(all, Instant.now().plusSeconds(1)).discrepancies()).isEmpty();
    }

    private TransferOutcome retryUntilAnswered(String key, TransferRequest request) throws InterruptedException {
        RuntimeException last = null;
        for (int attempt = 0; attempt < 20; attempt++) {
            try {
                return transferService.executeIdempotent(CLIENT, key, request);
            } catch (RuntimeException e) {
                last = e; // pool may still be evicting dead connections
                Thread.sleep(250);
            }
        }
        throw new AssertionError("retry of " + key + " never succeeded", last);
    }

    private static TransferRequest transfer(UUID from, UUID to, long amount) {
        return new TransferRequest("chaos transfer", "USD", List.of(
                new TransferLegRequest(from, PostingDirection.CREDIT, amount),
                new TransferLegRequest(to, PostingDirection.DEBIT, amount)));
    }
}
