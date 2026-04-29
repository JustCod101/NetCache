package com.netcache.common.util;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HashUtilTest {
    @Test
    void hashIsDeterministicAndUsesBytesDirectly() {
        byte[] key = "cache-key".getBytes(StandardCharsets.UTF_8);

        assertThat(HashUtil.hash64(key)).isEqualTo(HashUtil.hash64(key.clone()));
        assertThat(HashUtil.hash64(key)).isNotEqualTo(HashUtil.hash64("cache-key-2".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void supportsOffsetAndLength() {
        byte[] bytes = "--abc--".getBytes(StandardCharsets.UTF_8);

        assertThat(HashUtil.murmur3X64Lower64(bytes, 2, 3, 0))
                .isEqualTo(HashUtil.hash64("abc".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void validatesBounds() {
        assertThatThrownBy(() -> HashUtil.murmur3X64Lower64(new byte[]{1}, 1, 1, 0))
                .isInstanceOf(IndexOutOfBoundsException.class);
    }
}
