package com.netcache.storage;

import java.util.Objects;

/**
 * 表示普通字符串值，是存储层里最常见的货箱。
 * 它负责把原始字节数组和过期、访问信息绑在一起。
 * 没有它，存储层只能直接暴露裸 byte[]，很难统一处理
 * 防御性拷贝、TTL 和 LRU 元数据。
 *
 * <p>上游由 {@link StorageEngine#set} 写入，也会被
 * {@link StorageEngine#get} 读取。</p>
 *
 * <p>线程安全说明：这是不可变 record；构造和读取时都做拷贝，
 * 避免外部线程偷偷改内部数组。</p>
 *
 * <pre>{@code
 * StringValue value = new StringValue(data, expireAtMs, nowMs);
 * byte[] bytes = value.value();
 * StringValue touched = value.withLastAccessMs(nowMs + 1);
 * }</pre>
 *
 * @param value 实际存储的字节内容
 * @param expireAtMs 绝对过期时间；0 表示永不过期
 * @param lastAccessMs 最近访问时间
 */
public record StringValue(byte[] value, long expireAtMs, long lastAccessMs) implements StoredValue {
    /**
     * 创建字符串值并做防御性拷贝。
     *
     * @implNote 这里在入口处先 clone，一次性切断外部数组别名，
     *     后续 record 才能真正具备不可变语义。
     */
    public StringValue {
        value = Objects.requireNonNull(value, "value").clone();
    }

    /**
     * 返回值内容的只读副本。
     *
     * @return 新拷贝出的字节数组，调用方修改它不会污染存储层
     * @implNote 每次都返回副本，代价是一次拷贝，换来并发读写下
     *     更稳定的封装边界。
     */
    @Override
    public byte[] value() {
        return value.clone();
    }

    /**
     * 计算字符串值自身的载荷大小。
     *
     * @return 字节数组长度，时间戳元数据不计入其中
     */
    @Override
    public int sizeBytes() {
        return value.length;
    }

    /**
     * 复制当前值并刷新访问时间。
     *
     * @param lastAccessMs 新的最近访问时间
     * @return 新的 {@link StringValue} 实例
     */
    @Override
    public StringValue withLastAccessMs(long lastAccessMs) {
        return new StringValue(value, expireAtMs, lastAccessMs);
    }

    /**
     * 复制当前值并刷新过期时间。
     *
     * @param expireAtMs 新的绝对过期时间；0 表示取消 TTL
     * @return 新的 {@link StringValue} 实例
     */
    @Override
    public StringValue withExpireAtMs(long expireAtMs) {
        return new StringValue(value, expireAtMs, lastAccessMs);
    }
}
