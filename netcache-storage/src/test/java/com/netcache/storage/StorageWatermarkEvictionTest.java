package com.netcache.storage;

import com.netcache.common.ByteKey;
import com.netcache.common.exception.OomGuardException;
import com.netcache.storage.memory.MemoryWatermark;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StorageWatermarkEvictionTest {

    @Test
    void lruEvictionStabilizesEntryCountAtHighWatermark() {
        AtomicLong simulatedUsageBits = new AtomicLong(Double.doubleToLongBits(0.0));
        MemoryWatermark watermark = new MemoryWatermark(0.85, 0.92,
                () -> Double.longBitsToDouble(simulatedUsageBits.get()));

        try (StorageEngine engine = new StorageEngine(watermark)) {
            byte[] value = value();

            for (int i = 0; i < 5000; i++) {
                engine.set(key(i), value, Duration.ZERO);
            }
            assertThat(engine.size()).isEqualTo(5000);

            simulatedUsageBits.set(Double.doubleToLongBits(0.86));
            assertThat(watermark.isHigh()).isTrue();
            assertThat(watermark.isDanger()).isFalse();

            for (int i = 5000; i < 6000; i++) {
                engine.set(key(i), value, Duration.ZERO);
            }

            assertThat(engine.size())
                    .as("high watermark writes should evict roughly one old entry per new entry")
                    .isBetween(4900, 5100);
        }
    }

    @Test
    void dangerWatermarkRejectsNewWrites() {
        MemoryWatermark watermark = new MemoryWatermark(0.85, 0.92, () -> 0.93);

        try (StorageEngine engine = new StorageEngine(watermark)) {
            assertThat(watermark.isDanger()).isTrue();
            assertThatThrownBy(() -> engine.set(key(1), value(), Duration.ZERO))
                    .isInstanceOf(OomGuardException.class);
        }
    }

    private static ByteKey key(int index) {
        return new ByteKey(("key-" + index).getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] value() {
        byte[] value = new byte[1024];
        for (int i = 0; i < value.length; i++) {
            value[i] = (byte) ('A' + (i % 26));
        }
        return value;
    }
}
