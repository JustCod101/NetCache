package com.netcache.common.util;

import java.util.Objects;

public final class HashUtil {
    private static final long C1 = 0x87c37b91114253d5L;
    private static final long C2 = 0x4cf5ad432745937fL;

    private HashUtil() {
    }

    public static long hash64(byte[] bytes) {
        Objects.requireNonNull(bytes, "bytes");
        return murmur3X64Lower64(bytes, 0, bytes.length, 0);
    }

    public static long murmur3X64Lower64(byte[] bytes, int offset, int length, int seed) {
        Objects.requireNonNull(bytes, "bytes");
        if (offset < 0 || length < 0 || offset > bytes.length - length) {
            throw new IndexOutOfBoundsException("offset/length outside byte array");
        }

        long h1 = seed;
        long h2 = seed;
        int roundedEnd = offset + (length & 0xfffffff0);

        for (int i = offset; i < roundedEnd; i += 16) {
            long k1 = getLongLittleEndian(bytes, i);
            long k2 = getLongLittleEndian(bytes, i + 8);

            k1 *= C1;
            k1 = Long.rotateLeft(k1, 31);
            k1 *= C2;
            h1 ^= k1;

            h1 = Long.rotateLeft(h1, 27);
            h1 += h2;
            h1 = h1 * 5 + 0x52dce729;

            k2 *= C2;
            k2 = Long.rotateLeft(k2, 33);
            k2 *= C1;
            h2 ^= k2;

            h2 = Long.rotateLeft(h2, 31);
            h2 += h1;
            h2 = h2 * 5 + 0x38495ab5;
        }

        long k1 = 0;
        long k2 = 0;
        int tail = roundedEnd;
        switch (length & 15) {
            case 15:
                k2 ^= (bytes[tail + 14] & 0xffL) << 48;
            case 14:
                k2 ^= (bytes[tail + 13] & 0xffL) << 40;
            case 13:
                k2 ^= (bytes[tail + 12] & 0xffL) << 32;
            case 12:
                k2 ^= (bytes[tail + 11] & 0xffL) << 24;
            case 11:
                k2 ^= (bytes[tail + 10] & 0xffL) << 16;
            case 10:
                k2 ^= (bytes[tail + 9] & 0xffL) << 8;
            case 9:
                k2 ^= bytes[tail + 8] & 0xffL;
                k2 *= C2;
                k2 = Long.rotateLeft(k2, 33);
                k2 *= C1;
                h2 ^= k2;
            case 8:
                k1 ^= (bytes[tail + 7] & 0xffL) << 56;
            case 7:
                k1 ^= (bytes[tail + 6] & 0xffL) << 48;
            case 6:
                k1 ^= (bytes[tail + 5] & 0xffL) << 40;
            case 5:
                k1 ^= (bytes[tail + 4] & 0xffL) << 32;
            case 4:
                k1 ^= (bytes[tail + 3] & 0xffL) << 24;
            case 3:
                k1 ^= (bytes[tail + 2] & 0xffL) << 16;
            case 2:
                k1 ^= (bytes[tail + 1] & 0xffL) << 8;
            case 1:
                k1 ^= bytes[tail] & 0xffL;
                k1 *= C1;
                k1 = Long.rotateLeft(k1, 31);
                k1 *= C2;
                h1 ^= k1;
            default:
                break;
        }

        h1 ^= length;
        h2 ^= length;
        h1 += h2;
        h2 += h1;
        h1 = fmix64(h1);
        h2 = fmix64(h2);
        h1 += h2;
        return h1;
    }

    private static long getLongLittleEndian(byte[] bytes, int offset) {
        return (bytes[offset] & 0xffL)
                | ((bytes[offset + 1] & 0xffL) << 8)
                | ((bytes[offset + 2] & 0xffL) << 16)
                | ((bytes[offset + 3] & 0xffL) << 24)
                | ((bytes[offset + 4] & 0xffL) << 32)
                | ((bytes[offset + 5] & 0xffL) << 40)
                | ((bytes[offset + 6] & 0xffL) << 48)
                | ((bytes[offset + 7] & 0xffL) << 56);
    }

    private static long fmix64(long value) {
        value ^= value >>> 33;
        value *= 0xff51afd7ed558ccdL;
        value ^= value >>> 33;
        value *= 0xc4ceb9fe1a85ec53L;
        value ^= value >>> 33;
        return value;
    }
}
