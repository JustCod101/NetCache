package com.netcache.benchmark;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class BenchmarkSmokeTest {

    @Test
    void endToEndSetAndGetOverTcp() throws Exception {
        LocalBenchmarkNode node = new LocalBenchmarkNode(16, 1024, 1);
        node.start();
        try {
            byte[] key = "smoke-key".getBytes(StandardCharsets.UTF_8);

            node.client().set(key, node.value());

            assertThat(node.client().get(key)).containsExactly(node.value());
        } finally {
            node.close();
        }
    }

    @Test
    void failoverScenarioCompletesWithinThreeSeconds() throws Exception {
        FailoverScenario.Result result = new FailoverScenario().run();

        assertThat(result.durationMs()).isLessThan(3_000L);
        assertThat(result.epoch()).isEqualTo(2L);
    }

    @Test
    void concurrentClientsCompleteSetAndGetOverTcp() throws Exception {
        int threads = 4;
        int operationsPerThread = 128;
        LocalBenchmarkNode node = new LocalBenchmarkNode(4096, 1024, threads);
        node.start();
        try {
            CountDownLatch startLatch = new CountDownLatch(1);
            AtomicInteger threadIds = new AtomicInteger();
            ExecutorService executor = Executors.newFixedThreadPool(threads, task -> {
                Thread thread = new Thread(task);
                thread.setName("nc-benchmark-smoke-" + threadIds.incrementAndGet());
                return thread;
            });
            List<Future<?>> futures = new ArrayList<>(threads);

            for (int t = 0; t < threads; t++) {
                int partition = t;
                futures.add(executor.submit(() -> {
                    startLatch.await();
                    for (int i = 0; i < operationsPerThread; i++) {
                        byte[] key = ("concurrent-" + partition + '-' + i).getBytes(StandardCharsets.UTF_8);
                        node.client().set(key, node.value());
                        assertThat(node.client().get(key)).containsExactly(node.value());
                    }
                    return null;
                }));
            }

            startLatch.countDown();
            for (Future<?> f : futures) {
                f.get(10, TimeUnit.SECONDS);
            }
            executor.shutdown();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        } finally {
            node.close();
        }
    }
}
