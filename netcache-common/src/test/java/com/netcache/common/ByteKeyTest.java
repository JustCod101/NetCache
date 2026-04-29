package com.netcache.common;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ByteKeyTest {
    @Test
    void copiesInputAndOutputBytes() {
        byte[] raw = "alpha".getBytes(StandardCharsets.UTF_8);
        ByteKey key = ByteKey.copyOf(raw);

        raw[0] = 'z';
        byte[] exposed = key.bytes();
        exposed[1] = 'z';

        assertThat(key.bytes()).containsExactly('a', 'l', 'p', 'h', 'a');
    }

    @Test
    void equalityAndHashCodeUseByteContent() {
        ByteKey first = ByteKey.copyOf(new byte[]{1, 2, 3});
        ByteKey second = ByteKey.copyOf(new byte[]{1, 2, 3});
        ByteKey different = ByteKey.copyOf(new byte[]{1, 2, 4});

        assertThat(first).isEqualTo(second).hasSameHashCodeAs(second);
        assertThat(first).isNotEqualTo(different);
    }

    @Test
    void comparesUnsignedBytes() {
        ByteKey low = ByteKey.copyOf(new byte[]{0x00});
        ByteKey high = ByteKey.copyOf(new byte[]{(byte) 0xff});

        assertThat(low.compareTo(high)).isNegative();
    }

    @Test
    void digestPrefixUsesRequestedLength() {
        ByteKey key = ByteKey.copyOf(new byte[]{0x01, 0x23, 0x45});

        assertThat(key.digestPrefix(2)).isEqualTo("0123");
        assertThatThrownBy(() -> key.digestPrefix(-1)).isInstanceOf(IllegalArgumentException.class);
    }
}
