package com.netcache.storage;

import com.netcache.common.ByteKey;
import com.netcache.common.exception.OomGuardException;
import com.netcache.storage.memory.MemoryWatermark;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StorageEngineTest {
    @Test
    void supportsSetGetDelExistsAndTtl() {
        try (StorageEngine engine = new StorageEngine()) {
            ByteKey key = key("alpha");

            engine.set(key, bytes("one"));

            assertThat(engine.get(key)).hasValue(bytes("one"));
            assertThat(engine.exists(key)).isTrue();
            assertThat(engine.ttl(key)).isEqualTo(-1L);
            assertThat(engine.del(key)).isTrue();
            assertThat(engine.get(key)).isEmpty();
            assertThat(engine.ttl(key)).isEqualTo(-2L);
        }
    }

    @Test
    void supportsIncrAndDecrAtomically() {
        try (StorageEngine engine = new StorageEngine()) {
            ByteKey key = key("counter");

            assertThat(engine.incr(key)).isEqualTo(1L);
            assertThat(engine.incr(key)).isEqualTo(2L);
            assertThat(engine.decr(key)).isEqualTo(1L);
            assertThat(engine.get(key)).hasValue(bytes("1"));
        }
    }

    @Test
    void lazyExpirationRemovesExpiredValues() throws InterruptedException {
        try (StorageEngine engine = new StorageEngine()) {
            ByteKey key = key("ttl-lazy");
            engine.set(key, bytes("short"), Duration.ofMillis(20));

            Thread.sleep(60);

            assertThat(engine.get(key)).isEmpty();
            assertThat(engine.size()).isZero();
        }
    }

    @Test
    void activeExpirationRemovesExpiredValuesWithinTolerance() throws InterruptedException {
        try (StorageEngine engine = new StorageEngine()) {
            ByteKey key = key("ttl-active");
            engine.set(key, bytes("short"), Duration.ofMillis(20));

            awaitSize(engine, 0, Duration.ofMillis(700));

            assertThat(engine.exists(key)).isFalse();
        }
    }

    @Test
    void highWatermarkEvictsOneLruEntryOnWrite() {
        MemoryWatermark watermark = new MemoryWatermark(0.50, 0.90, () -> 0.75);
        try (StorageEngine engine = new StorageEngine(watermark)) {
            ByteKey first = key("first");
            ByteKey second = key("second");

            engine.set(first, bytes("1"));
            engine.set(second, bytes("2"));

            assertThat(engine.size()).isLessThanOrEqualTo(1);
        }
    }

    @Test
    void dangerWatermarkRejectsWrites() {
        MemoryWatermark watermark = new MemoryWatermark(0.50, 0.90, () -> 0.95);
        try (StorageEngine engine = new StorageEngine(watermark)) {
            assertThatThrownBy(() -> engine.set(key("blocked"), bytes("value")))
                    .isInstanceOf(OomGuardException.class);
        }
    }

    @Test
    void concurrentSetGetOneMillionOperationsDoesNotLoseValues() throws Exception {
        int threads = 8;
        int operationsPerThread = 125_000;
        try (StorageEngine engine = new StorageEngine()) {
            var executor = Executors.newFixedThreadPool(threads, runnable -> {
                Thread thread = new Thread(runnable);
                thread.setName("nc-storage-test-" + thread.threadId());
                return thread;
            });
            CountDownLatch start = new CountDownLatch(1);
            List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
            for (int threadIndex = 0; threadIndex < threads; threadIndex++) {
                final int partition = threadIndex;
                futures.add(executor.submit(() -> {
                    start.await();
                    for (int i = 0; i < operationsPerThread; i++) {
                        ByteKey key = key("k-" + partition + '-' + i);
                        byte[] value = bytes("v-" + i);
                        engine.set(key, value);
                        if (!engine.get(key).filter(found -> java.util.Arrays.equals(found, value)).isPresent()) {
                            throw new AssertionError("lost value for " + key);
                        }
                    }
                    return null;
                }));
            }

            start.countDown();
            for (java.util.concurrent.Future<?> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
            executor.shutdown();

            assertThat(engine.size()).isEqualTo(threads * operationsPerThread);
        }
    }

    private static void awaitSize(StorageEngine engine, int expected, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (engine.size() == expected) {
                return;
            }
            Thread.sleep(10);
        }
        assertThat(engine.size()).isEqualTo(expected);
    }

    private static ByteKey key(String value) {
        return ByteKey.copyOf(bytes(value));
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
