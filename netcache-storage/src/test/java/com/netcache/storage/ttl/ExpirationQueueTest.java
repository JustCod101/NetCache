package com.netcache.storage.ttl;

import com.netcache.common.ByteKey;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class ExpirationQueueTest {
    @Test
    void emitsExpiredEventsFromScheduledScan() throws InterruptedException {
        ByteKey key = ByteKey.copyOf(new byte[]{1});
        try (ExpirationQueue queue = new ExpirationQueue(10)) {
            CountDownLatch latch = new CountDownLatch(1);
            queue.start((expiredKey, expireAtMs) -> latch.countDown());
            queue.schedule(key, System.currentTimeMillis() + 20);

            assertThat(latch.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(queue.pollExpiredEvent()).isEqualTo(key);
        }
    }
}
