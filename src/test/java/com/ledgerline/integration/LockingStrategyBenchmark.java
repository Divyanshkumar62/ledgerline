package com.ledgerline.integration;

import com.ledgerline.application.dto.TransferRequest;
import com.ledgerline.application.strategy.ConcurrencyStrategyType;
import com.ledgerline.domain.model.Account;
import com.ledgerline.domain.model.AccountType;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.SplittableRandom;
import java.util.UUID;
import java.util.function.IntFunction;
import java.util.stream.IntStream;

/**
 * ADR-002 data: throughput, latency percentiles and rejection rate per strategy, under
 * (1) one hot source account and (2) low contention across 100 accounts. ADR-014 data: {@link #hotSinkStriping()}.
 * Run with {@code ./gradlew benchmark}; the table is written to build/benchmark/locking-strategies.md.
 *
 * Closed-loop load: {@link #THREADS} workers each send the next transfer as soon as the last
 * one finishes, so this measures capacity under contention, not behaviour at a fixed arrival rate.
 */
@Tag("benchmark")
class LockingStrategyBenchmark extends PostgresIntegrationTest {

    private static final int THREADS = POOL_SIZE;
    private static final int WARMUP = 300;
    private static final int MEASURED = 3_000;
    private static final long RICH = 1_000_000_000L;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void compareStrategies() throws Exception {
        StringBuilder md = new StringBuilder("# Locking strategy benchmark (ADR-002)\n\n")
                .append(environment()).append('\n')
                .append("| Scenario | Strategy | Succeeded | Rejected | Rejection % | Throughput (tx/s) | p50 ms | p95 ms | p99 ms | max ms |\n")
                .append("|---|---|---|---|---|---|---|---|---|---|\n");

        for (ConcurrencyStrategyType strategy : ConcurrencyStrategyType.values()) {
            UUID hot = fundedAccount(RICH).getId();
            List<UUID> sinks = IntStream.range(0, 10).mapToObj(i -> newAccount().getId()).toList();
            IntFunction<TransferRequest> hotLoad = i -> transfer(hot, sinks.get(i % sinks.size()), 1);
            md.append(row("Hot account (1 source -> 10 sinks)", strategy, hotLoad));
        }

        for (ConcurrencyStrategyType strategy : ConcurrencyStrategyType.values()) {
            List<UUID> accounts = IntStream.range(0, 100).mapToObj(i -> fundedAccount(RICH).getId()).toList();
            IntFunction<TransferRequest> spreadLoad = i -> {
                SplittableRandom rnd = new SplittableRandom(i);
                int from = rnd.nextInt(accounts.size());
                int to = (from + 1 + rnd.nextInt(accounts.size() - 1)) % accounts.size();
                return transfer(accounts.get(from), accounts.get(to), 1);
            };
            md.append(row("Low contention (random pairs of 100)", strategy, spreadLoad));
        }

        Path out = Path.of("build", "benchmark", "locking-strategies.md");
        Files.createDirectories(out.getParent());
        Files.writeString(out, md);
        System.out.println(md);
    }

    /**
     * ADR-014 data: many sources paying ONE receive-only sink (e.g. platform fees), strategy A. The sink is a plain
     * account, then striped into 1 / 4 / 8 / 16 stripes. N = 1 is the control: it goes through the router like the
     * others but puts every transfer on a single row, so any gain over it comes from spreading the lock, not from
     * the code path. (The existing hot-account scenario is a hot SOURCE, which striping cannot help.)
     * Run alone with {@code ./gradlew benchmark --tests '*LockingStrategyBenchmark.hotSinkStriping'}.
     */
    @Test
    void hotSinkStriping() throws Exception {
        StringBuilder md = new StringBuilder("# Hot-sink striping benchmark (ADR-014)\n\n")
                .append(environment()).append('\n')
                .append("| Scenario | Strategy | Succeeded | Rejected | Rejection % | Throughput (tx/s) | p50 ms | p95 ms | p99 ms | max ms |\n")
                .append("|---|---|---|---|---|---|---|---|---|---|\n");
        ConcurrencyStrategyType strategy = ConcurrencyStrategyType.ORDERED_PESSIMISTIC;
        List<UUID> sources = IntStream.range(0, 100).mapToObj(i -> fundedAccount(RICH).getId()).toList();

        UUID plain = accountRepository.save(new Account(AccountType.ASSET, "USD", false)).getId();
        md.append(row("Hot sink (100 sources -> 1 sink), unstriped", strategy, sinkLoad(sources, plain)));
        for (int stripes : new int[] {1, 4, 8, 16}) {
            UUID sink = stripedAccount(stripes, Account.SYSTEM_OWNER).getId();
            md.append(row("Hot sink (100 sources -> 1 sink), " + stripes + " stripe" + (stripes == 1 ? " (control)" : "s"),
                    strategy, sinkLoad(sources, sink)));
        }

        Path out = Path.of("build", "benchmark", "hot-sink-striping.md");
        Files.createDirectories(out.getParent());
        Files.writeString(out, md);
        System.out.println(md);
    }

    private static IntFunction<TransferRequest> sinkLoad(List<UUID> sources, UUID sink) {
        return i -> transfer(sources.get(new SplittableRandom(i).nextInt(sources.size())), sink, 1);
    }

    private String row(String scenario, ConcurrencyStrategyType strategy, IntFunction<TransferRequest> load) throws Exception {
        runConcurrently(strategy, THREADS, WARMUP, load); // JIT, connection pool and buffer cache warm-up
        Outcomes out = runConcurrently(strategy, THREADS, MEASURED, i -> load.apply(WARMUP + i));

        long[] sorted = out.latencyNanos().clone();
        Arrays.sort(sorted);
        int rejected = out.failures().size();
        double seconds = out.wallNanos() / 1e9;
        return "| %s | %s | %d | %d | %.1f | %.0f | %.1f | %.1f | %.1f | %.1f |%n".formatted(
                scenario, strategy, out.succeeded(), rejected, 100.0 * rejected / MEASURED,
                out.succeeded() / seconds, ms(sorted, 0.50), ms(sorted, 0.95), ms(sorted, 0.99), ms(sorted, 1.0));
    }

    private static double ms(long[] sortedNanos, double percentile) {
        int index = (int) Math.ceil(percentile * sortedNanos.length) - 1;
        return sortedNanos[Math.max(index, 0)] / 1e6;
    }

    private String environment() {
        Runtime rt = Runtime.getRuntime();
        return """
                - Workers: %d threads, Hikari pool %d, %d measured transfers per row (+%d warm-up)
                - CPU cores visible to JVM: %d, max heap: %d MB
                - OS: %s %s (%s)
                - JVM: %s %s
                - Database: %s (Testcontainers; on Windows/macOS this runs inside the Docker VM)
                """.formatted(THREADS, POOL_SIZE, MEASURED, WARMUP,
                rt.availableProcessors(), rt.maxMemory() / (1024 * 1024),
                System.getProperty("os.name"), System.getProperty("os.version"), System.getProperty("os.arch"),
                System.getProperty("java.vendor"), System.getProperty("java.version"),
                jdbcTemplate.queryForObject("SELECT version()", String.class));
    }
}
