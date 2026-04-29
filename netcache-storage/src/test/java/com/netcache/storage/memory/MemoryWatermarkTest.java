package com.netcache.storage.memory;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MemoryWatermarkTest {
    @Test
    void reportsHighAndDangerStatesFromSampler() {
        MemoryWatermark high = new MemoryWatermark(0.50, 0.90, () -> 0.75);
        MemoryWatermark danger = new MemoryWatermark(0.50, 0.90, () -> 0.95);

        assertThat(high.isHigh()).isTrue();
        assertThat(high.isDanger()).isFalse();
        assertThat(danger.isDanger()).isTrue();
    }

    @Test
    void validatesThresholdOrder() {
        assertThatThrownBy(() -> new MemoryWatermark(0.90, 0.80, () -> 0.0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
