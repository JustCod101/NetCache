package com.netcache.common.util;

import java.util.Objects;

/**
 * 哈希工具类 —— NetCache 的「分拣尺子」。
 * <p>
 * 它负责把任意字节序列稳定映射到 64 位哈希值，供一致性哈希、分片和快速定位使用。
 * 没有统一实现的话，不同模块各算各的哈希，会让同一个 key 在不同地方落到不同槽位。
 * <p>
 * 协作关系：
 * <ul>
 *   <li>上游由路由、分片、索引等模块传入原始字节内容</li>
 *   <li>下游依赖 MurmurHash3 x64 128-bit 算法并只取低 64 位结果</li>
 * </ul>
 * 线程安全：全部方法无状态、只读入参，天然线程安全。
 * <p>
 * 典型用例：
 * <pre>
 * byte[] key = "user:1".getBytes();
 * long slot = HashUtil.hash64(key);
 * long custom = HashUtil.murmur3X64Lower64(key, 0, key.length, 1234);
 * </pre>
 */
public final class HashUtil {
    // MurmurHash3 规定的混合常量，不是随便拍脑袋的魔数。
    private static final long C1 = 0x87c37b91114253d5L;
    // 与 C1 配对使用，负责把输入位模式充分打散。
    private static final long C2 = 0x4cf5ad432745937fL;

    private HashUtil() {
    }

    /**
     * 计算整段字节数组的 64 位哈希值。
     *
     * @param bytes 需要哈希的完整字节内容，不能为空
     * @return MurmurHash3 x64 128-bit 的低 64 位结果
     * @throws NullPointerException 当 {@code bytes} 为 {@code null} 时抛出
     * @implNote 默认种子固定为 0，方便整个系统对同一 key 得到一致结果。
     */
    public static long hash64(byte[] bytes) {
        Objects.requireNonNull(bytes, "bytes");
        return murmur3X64Lower64(bytes, 0, bytes.length, 0);
    }

    /**
     * 计算指定数组片段的 MurmurHash3 低 64 位值。
     *
     * @param bytes 原始字节数组，不能为空
     * @param offset 起始偏移，单位为字节，取值范围为 {@code [0, bytes.length]}
     * @param length 参与哈希的长度，单位为字节，必须大于等于 0
     * @param seed 哈希种子，用于不同命名空间之间做扰动隔离
     * @return 128 位 MurmurHash3 结果中的低 64 位
     * @throws NullPointerException 当 {@code bytes} 为 {@code null} 时抛出
     * @throws IndexOutOfBoundsException 当 {@code offset/length} 越界时抛出
     * @implNote 时间复杂度为 O(n)。主体按 16 字节块推进，尾巴再用 switch 收尾。
     */
    public static long murmur3X64Lower64(byte[] bytes, int offset, int length, int seed) {
        Objects.requireNonNull(bytes, "bytes");
        if (offset < 0 || length < 0 || offset > bytes.length - length) {
            throw new IndexOutOfBoundsException("offset/length outside byte array");
        }

        long h1 = seed;
        long h2 = seed;
        // 0xfffffff0 会把低 4 位清零，相当于向下对齐到 16 字节块边界。
        int roundedEnd = offset + (length & 0xfffffff0);

        for (int i = offset; i < roundedEnd; i += 16) {
            long k1 = getLongLittleEndian(bytes, i);
            long k2 = getLongLittleEndian(bytes, i + 8);

            // 每个 64 位块都要经历“乘常量 -> 旋转 -> 再乘常量”，
            // 这样高低位的信息会像洗牌一样扩散到整个结果空间。
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
        /*
         * 尾块处理示意：
         * 剩余字节: [b0 b1 ... b14]
         * k1 吃前 8 个字节，k2 吃后 7 个字节。
         * 每个 case 故意贯穿落下，像把零散积木继续拼成 64 位块。
         */
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

        // 长度要混进最终结果，否则前缀相同但长度不同的输入更容易碰撞。
        h1 ^= length;
        h2 ^= length;
        h1 += h2;
        h2 += h1;
        h1 = fmix64(h1);
        h2 = fmix64(h2);
        h1 += h2;
        return h1;
    }

    /**
     * 按小端序读取 8 个字节为 long。
     *
     * @param bytes 源字节数组
     * @param offset 起始位置，要求至少还能读出 8 个字节
     * @return 按 little-endian 组装出的 64 位整数
     * @implNote MurmurHash3 x64 版本规定按小端取块，不能直接依赖平台字节序。
     */
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

    /**
     * 对中间态做最终混合（finalization mix）。
     *
     * @param value 待打散的 64 位中间值
     * @return 雪崩效应更强的最终值
     * @implNote fmix64 是 MurmurHash3 标准收尾步骤，目的是让任意一位输入变化
     *           都尽量传播到输出的多数位上。
     */
    private static long fmix64(long value) {
        // 右移异或 + 乘大常量，是经典 avalanche 收尾组合。
        value ^= value >>> 33;
        value *= 0xff51afd7ed558ccdL;
        value ^= value >>> 33;
        value *= 0xc4ceb9fe1a85ec53L;
        value ^= value >>> 33;
        return value;
    }
}
