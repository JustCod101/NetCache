package com.netcache.common;

import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;

/**
 * 二进制 Key 包装器 —— NetCache 的「防震快递盒」。
 * <p>
 * 它把裸 {@code byte[]} 包成一个可比较、可放进集合的稳定对象，避免业务层把
 * 可变数组直接当键使用。没有它的话，外部一旦改动原数组，Map/Set 里的定位就会
 * 飘掉，哈希桶和排序结果都可能失真。
 * <p>
 * 协作关系：
 * <ul>
 *   <li>上游由协议层、存储层和路由层传入原始 key 字节</li>
 *   <li>下游依赖 {@link Arrays} 做内容比较，依赖 {@link HexFormat} 输出前缀摘要</li>
 * </ul>
 * 线程安全：实例不可变，构造时和读取时都做防御性拷贝，因此天然线程安全。
 * 多线程之间可以直接共享同一个 {@code ByteKey}。
 * <p>
 * 典型用例：
 * <pre>
 * byte[] raw = "user:1".getBytes();
 * ByteKey key = ByteKey.copyOf(raw);
 * cache.put(key, value);
 * log.debug("{}", key.digestPrefix(8));
 * </pre>
 */
public final class ByteKey implements Comparable<ByteKey> {
    // HexFormat 直接输出十六进制，避免手写表驱动转换。
    private static final HexFormat HEX = HexFormat.of();

    // 必须持有副本而不是原数组，因为 byte[] 天生可变。
    private final byte[] bytes;
    // 预计算哈希值，避免热路径上重复扫描整个数组。
    private final int hash;

    /**
     * 构造一个不可变的二进制键。
     *
     * @param bytes 业务 key 的原始字节内容，不能为空
     * @throws NullPointerException 当 {@code bytes} 为 {@code null} 时抛出
     * @implNote 构造时立刻 {@code clone()}，把调用方后续修改隔离在对象外部。
     */
    public ByteKey(byte[] bytes) {
        this.bytes = Objects.requireNonNull(bytes, "bytes").clone();
        this.hash = Arrays.hashCode(this.bytes);
    }

    /**
     * 复制给定字节数组并生成键对象。
     *
     * @param bytes 需要封装的原始 key 字节，业务上通常来自协议解码或 API 入参
     * @return 新建的不可变键对象；不会复用入参数组引用
     * @throws NullPointerException 当 {@code bytes} 为 {@code null} 时抛出
     * @implNote 这里直接委托构造器，统一复用防御性拷贝逻辑。
     */
    public static ByteKey copyOf(byte[] bytes) {
        return new ByteKey(bytes);
    }

    /**
     * 返回键内容的一个副本。
     *
     * @return 新分配的字节数组；长度与内部内容一致，永远不会返回 {@code null}
     * @implNote 返回副本而不是内部数组，防止调用方拿到引用后破坏不可变语义。
     */
    public byte[] bytes() {
        return bytes.clone();
    }

    /**
     * 返回键占用的字节数。
     *
     * @return key 长度，单位为字节，取值范围为 {@code [0, Integer.MAX_VALUE]}
     * @implNote 直接读取数组长度，时间复杂度为 O(1)。
     */
    public int sizeBytes() {
        return bytes.length;
    }

    /**
     * 生成十六进制摘要前缀，方便日志打点。
     *
     * @param maxBytes 最多输出多少个原始字节，单位为字节，必须大于等于 0
     * @return 十六进制字符串；当 {@code maxBytes} 为 0 时返回空串，
     *         当超出 key 长度时只输出全部内容
     * @throws IllegalArgumentException 当 {@code maxBytes} 小于 0 时抛出
     * @implNote 只截取前缀而不输出完整 key，主要是为了兼顾可读性和日志体积。
     */
    public String digestPrefix(int maxBytes) {
        if (maxBytes < 0) {
            throw new IllegalArgumentException("maxBytes must be non-negative");
        }
        int length = Math.min(bytes.length, maxBytes);
        return HEX.formatHex(bytes, 0, length);
    }

    /**
     * 按无符号字典序比较两个二进制键。
     *
     * @param other 另一个待比较的键，不能为空
     * @return 负数表示当前对象更小，0 表示内容完全一致，正数表示更大
     * @throws NullPointerException 当 {@code other} 为 {@code null} 时抛出
     * @implNote 使用 {@link Arrays#compareUnsigned(byte[], byte[])}，
     *           避免 Java 有符号 byte 把 0xFF 错看成 -1。
     */
    @Override
    public int compareTo(ByteKey other) {
        Objects.requireNonNull(other, "other");
        return Arrays.compareUnsigned(bytes, other.bytes);
    }

    /**
     * 判断两个键是否表示同一段二进制内容。
     *
     * @param other 另一个待比较对象，可以为任意类型
     * @return 只有当对方也是 {@code ByteKey} 且字节内容逐位相等时才返回 {@code true}
     * @implNote 先做引用短路，再做内容比较，兼顾常见命中场景和语义正确性。
     */
    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof ByteKey that && Arrays.equals(bytes, that.bytes);
    }

    /**
     * 返回当前键的哈希值。
     *
     * @return 基于字节内容计算出的稳定哈希值
     * @implNote 哈希值在构造阶段缓存，避免哈希表热路径上重复 O(n) 扫描数组。
     */
    @Override
    public int hashCode() {
        return hash;
    }

    /**
     * 生成适合日志与调试的字符串描述。
     *
     * @return 包含长度和前 16 字节摘要的文本，不返回完整内容以避免日志过长
     * @implNote 调试时更关心“是不是同一个 key 前缀”，而不是把全部二进制刷进日志。
     */
    @Override
    public String toString() {
        return "ByteKey[len=" + bytes.length + ", prefix=" + digestPrefix(16) + "]";
    }
}
