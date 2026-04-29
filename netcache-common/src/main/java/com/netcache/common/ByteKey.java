package com.netcache.common;

import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;

public final class ByteKey implements Comparable<ByteKey> {
    private static final HexFormat HEX = HexFormat.of();

    private final byte[] bytes;
    private final int hash;

    public ByteKey(byte[] bytes) {
        this.bytes = Objects.requireNonNull(bytes, "bytes").clone();
        this.hash = Arrays.hashCode(this.bytes);
    }

    public static ByteKey copyOf(byte[] bytes) {
        return new ByteKey(bytes);
    }

    public byte[] bytes() {
        return bytes.clone();
    }

    public int sizeBytes() {
        return bytes.length;
    }

    public String digestPrefix(int maxBytes) {
        if (maxBytes < 0) {
            throw new IllegalArgumentException("maxBytes must be non-negative");
        }
        int length = Math.min(bytes.length, maxBytes);
        return HEX.formatHex(bytes, 0, length);
    }

    @Override
    public int compareTo(ByteKey other) {
        Objects.requireNonNull(other, "other");
        return Arrays.compareUnsigned(bytes, other.bytes);
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof ByteKey that && Arrays.equals(bytes, that.bytes);
    }

    @Override
    public int hashCode() {
        return hash;
    }

    @Override
    public String toString() {
        return "ByteKey[len=" + bytes.length + ", prefix=" + digestPrefix(16) + "]";
    }
}
