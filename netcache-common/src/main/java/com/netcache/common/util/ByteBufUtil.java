package com.netcache.common.util;

import io.netty.buffer.ByteBuf;
import io.netty.util.ReferenceCountUtil;

import java.util.Objects;

public final class ByteBufUtil {
    private ByteBufUtil() {
    }

    public static ByteBuf readRetainedSlice(ByteBuf source, int length) {
        requireReadable(source, length);
        return source.readRetainedSlice(length);
    }

    public static ByteBuf retainedSlice(ByteBuf source, int index, int length) {
        Objects.requireNonNull(source, "source");
        if (index < 0 || length < 0 || index > source.capacity() - length) {
            throw new IndexOutOfBoundsException("index/length outside ByteBuf capacity");
        }
        return source.retainedSlice(index, length);
    }

    public static void requireReadable(ByteBuf source, int length) {
        Objects.requireNonNull(source, "source");
        if (length < 0) {
            throw new IllegalArgumentException("length must be non-negative");
        }
        if (source.readableBytes() < length) {
            throw new IndexOutOfBoundsException("required " + length + " readable bytes but found " + source.readableBytes());
        }
    }

    public static byte[] copyReadableBytes(ByteBuf source) {
        Objects.requireNonNull(source, "source");
        byte[] bytes = new byte[source.readableBytes()];
        source.getBytes(source.readerIndex(), bytes);
        return bytes;
    }

    public static void assertEqual(ByteBuf expected, ByteBuf actual) {
        Objects.requireNonNull(expected, "expected");
        Objects.requireNonNull(actual, "actual");
        int expectedReadable = expected.readableBytes();
        int actualReadable = actual.readableBytes();
        if (expectedReadable != actualReadable) {
            throw new AssertionError("ByteBuf readable bytes differ: expected " + expectedReadable + " but was " + actualReadable);
        }

        int expectedIndex = expected.readerIndex();
        int actualIndex = actual.readerIndex();
        for (int i = 0; i < expectedReadable; i++) {
            byte expectedByte = expected.getByte(expectedIndex + i);
            byte actualByte = actual.getByte(actualIndex + i);
            if (expectedByte != actualByte) {
                throw new AssertionError("ByteBuf differs at readable offset " + i + ": expected " + expectedByte + " but was " + actualByte);
            }
        }
    }

    public static boolean release(Object reference) {
        return ReferenceCountUtil.release(reference);
    }
}
