package com.netcache.storage;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CounterValueTest {
    @Test
    void addReturnsNewCounterWithUpdatedAccessTime() {
        CounterValue value = new CounterValue(10, 0, 1);

        CounterValue updated = value.add(5, 20);

        assertThat(updated.value()).isEqualTo(15);
        assertThat(updated.lastAccessMs()).isEqualTo(20);
        assertThat(value.value()).isEqualTo(10);
    }
}
