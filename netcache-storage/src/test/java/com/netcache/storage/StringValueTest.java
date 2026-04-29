package com.netcache.storage;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StringValueTest {
    @Test
    void copiesStoredBytes() {
        byte[] raw = new byte[]{1, 2, 3};
        StringValue value = new StringValue(raw, 0, 10);

        raw[0] = 9;
        byte[] exposed = value.value();
        exposed[1] = 9;

        assertThat(value.value()).containsExactly(1, 2, 3);
        assertThat(value.sizeBytes()).isEqualTo(3);
    }

    @Test
    void detectsExpiration() {
        assertThat(new StringValue(new byte[]{1}, 100, 0).isExpired(100)).isTrue();
        assertThat(new StringValue(new byte[]{1}, 0, 0).isExpired(Long.MAX_VALUE)).isFalse();
    }
}
