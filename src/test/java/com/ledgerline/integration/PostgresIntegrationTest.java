package com.ledgerline.integration;

import com.ledgerline.application.dto.TransferLegRequest;
import com.ledgerline.application.dto.TransferRequest;
import com.ledgerline.application.service.ApiClientService;
import com.ledgerline.application.service.TransferService;
import com.ledgerline.application.strategy.ConcurrencyStrategyType;
import com.ledgerline.domain.model.Account;
import com.ledgerline.domain.model.AccountType;
import com.ledgerline.domain.model.ApiClient;
import com.ledgerline.domain.model.PostingDirection;
import com.ledgerline.domain.repository.AccountRepository;
import com.ledgerline.domain.repository.PostingRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntConsumer;
import java.util.function.IntFunction;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * One PostgreSQL container for the whole test JVM (singleton container pattern).
 * A per-class @Container would stop the database between classes while Spring's cached
 * application context still points at it.
 */
@SpringBootTest(properties = {
        "spring.datasource.hikari.maximum-pool-size=" + PostgresIntegrationTest.POOL_SIZE,
        "spring.datasource.hikari.minimum-idle=2",
        // Background workers off: tests drive the relay and the audit explicitly, so results are deterministic.
        "ledgerline.outbox.relay.enabled=false",
        "ledgerline.audit.enabled=false",
        "ledgerline.retention.cron=-",
        "ledgerline.security.bootstrap-admin-key=" + PostgresIntegrationTest.ADMIN_KEY
})
public abstract class PostgresIntegrationTest {

    /** Connection pool size; concurrency tests use the same number of worker threads. */
    protected static final int POOL_SIZE = 32;

    /** Key of the bootstrap admin created in every test context. */
    protected static final String ADMIN_KEY = "test-admin-key-0123456789abcdef";

    @ServiceConnection
    // Every distinct Spring test context has its own pool; raise the server limit so they can coexist.
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16")
            .withCommand("postgres", "-c", "max_connections=300");

    static {
        postgres.start();
    }

    @Autowired
    protected TransferService transferService;

    @Autowired
    protected AccountRepository accountRepository;

    @Autowired
    protected PostingRepository postingRepository;

    @Autowired
    protected ApiClientService apiClients;

    /** A registered API client and its (plaintext) key. */
    protected record Credentials(String clientId, String apiKey) {
        public String bearer() {
            return "Bearer " + apiKey;
        }
    }

    protected Credentials newClient() {
        String id = "c-" + UUID.randomUUID().toString().substring(0, 13);
        return new Credentials(id, apiClients.create(id, "test client", ApiClient.Role.CLIENT).apiKey());
    }

    protected Account newAccount() {
        return newAccount(Account.SYSTEM_OWNER);
    }

    protected Account newAccount(String owner) {
        return accountRepository.save(new Account(AccountType.ASSET, "USD", true, owner));
    }

    protected Account fundedAccount(long amountMinor) {
        return fundedAccount(amountMinor, Account.SYSTEM_OWNER);
    }

    /**
     * Funds a new asset account the double-entry way: DEBIT the asset, CREDIT an equity
     * (owner capital) account. The balance therefore always equals the sum of postings.
     */
    protected Account fundedAccount(long amountMinor, String owner) {
        Account asset = newAccount(owner);
        Account capital = accountRepository.save(new Account(AccountType.EQUITY, "USD", false));
        transferService.executeTransfer(transfer(capital.getId(), asset.getId(), amountMinor));
        return accountRepository.findById(asset.getId()).orElseThrow();
    }

    protected static TransferRequest transfer(UUID from, UUID to, long amountMinor) {
        return new TransferRequest("transfer", "USD", List.of(
                new TransferLegRequest(from, PostingDirection.CREDIT, amountMinor),
                new TransferLegRequest(to, PostingDirection.DEBIT, amountMinor)));
    }

    protected long balanceOf(UUID accountId) {
        return accountRepository.findById(accountId).orElseThrow().getBalance();
    }

    /** No drift: the cached balance must equal the sum of the account's postings. */
    protected void assertBalanceMatchesPostings(UUID accountId) {
        Account account = accountRepository.findById(accountId).orElseThrow();
        long fromPostings = postingRepository.computeProjectedBalance(accountId, account.getType().getNormalBalance());
        assertThat(account.getBalance()).as("cached balance vs postings for %s", accountId).isEqualTo(fromPostings);
    }

    protected record Outcomes(int succeeded, List<Throwable> failures, long[] latencyNanos, long wallNanos) {
        long count(Class<? extends Throwable> type) {
            return failures.stream().filter(type::isInstance).count();
        }

        long deadlocks() {
            return failures.stream().filter(PostgresIntegrationTest::isDeadlock).count();
        }
    }

    /** Runs {@code transfers} requests on {@code threads} workers released at the same instant. */
    protected Outcomes runConcurrently(ConcurrencyStrategyType strategy, int threads, int transfers,
                                       IntFunction<TransferRequest> requestFor) throws Exception {
        return runConcurrently(threads, transfers, n -> transferService.executeTransfer(requestFor.apply(n), strategy));
    }

    /** Runs {@code transfers} calls of {@code task.accept(i)} on {@code threads} workers released at the same instant. */
    protected Outcomes runConcurrently(int threads, int transfers, IntConsumer task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger succeeded = new AtomicInteger();
        Queue<Throwable> failures = new ConcurrentLinkedQueue<>();
        long[] latency = new long[transfers];
        List<Future<?>> futures = new ArrayList<>(transfers);

        for (int i = 0; i < transfers; i++) {
            int n = i;
            futures.add(pool.submit(() -> {
                go.await();
                long t0 = System.nanoTime();
                try {
                    task.accept(n);
                    succeeded.incrementAndGet();
                } catch (RuntimeException e) {
                    failures.add(e);
                } finally {
                    latency[n] = System.nanoTime() - t0;
                }
                return null;
            }));
        }

        long start = System.nanoTime();
        go.countDown();
        for (Future<?> f : futures) {
            f.get(2, TimeUnit.MINUTES); // also publishes latency[] writes to this thread
        }
        long wall = System.nanoTime() - start;
        pool.shutdown();
        return new Outcomes(succeeded.get(), List.copyOf(failures), latency, wall);
    }

    /** PostgreSQL reports a deadlock victim with SQLSTATE 40P01, however Spring wraps it. */
    static boolean isDeadlock(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof SQLException sql && "40P01".equals(sql.getSQLState())) {
                return true;
            }
        }
        return false;
    }
}
