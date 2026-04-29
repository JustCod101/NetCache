package com.netcache.common.util;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ByteBufUtilTest {
    @Test
    void readRetainedSliceSharesBytesAndAdvancesReaderIndex() {
        ByteBuf source = Unpooled.buffer();
        source.writeBytes(new byte[]{1, 2, 3, 4});

        ByteBuf slice = ByteBufUtil.readRetainedSlice(source, 2);
        try {
            assertThat(source.readerIndex()).isEqualTo(2);
            assertThat(ByteBufUtil.copyReadableBytes(slice)).containsExactly(1, 2);

            slice.setByte(0, 9);
            assertThat(source.getByte(0)).isEqualTo((byte) 9);
        } finally {
            ByteBufUtil.release(slice);
            ByteBufUtil.release(source);
        }
    }

    @Test
    void assertEqualDoesNotChangeReaderIndexes() {
        ByteBuf expected = Unpooled.wrappedBuffer(new byte[]{0, 1, 2, 3});
        ByteBuf actual = Unpooled.wrappedBuffer(new byte[]{9, 1, 2, 3});
        expected.readerIndex(1);
        actual.readerIndex(1);

        ByteBufUtil.assertEqual(expected, actual);

        assertThat(expected.readerIndex()).isEqualTo(1);
        assertThat(actual.readerIndex()).isEqualTo(1);
    }

    @Test
    void assertEqualReportsMismatch() {
        ByteBuf expected = Unpooled.wrappedBuffer(new byte[]{1});
        ByteBuf actual = Unpooled.wrappedBuffer(new byte[]{2});

        assertThatThrownBy(() -> ByteBufUtil.assertEqual(expected, actual))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("differs at readable offset 0");
    }

    @Test
    void requireReadableRejectsShortBuffers() {
        ByteBuf source = Unpooled.wrappedBuffer(new byte[]{1});

        assertThatThrownBy(() -> ByteBufUtil.requireReadable(source, 2))
                .isInstanceOf(IndexOutOfBoundsException.class);
    }
}
