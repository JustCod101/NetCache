package com.netcache.storage;

/**
 * 表示整数计数器值，相当于存储层里的算盘格子。
 * 它专门服务 INCR/DECR 这类原子累加场景。
 * 没有独立类型，计数器就得反复在字符串和 long 之间转换，
 * 性能和错误边界都会更差。
 *
 * <p>上游主要由 {@link StorageEngine#incr}、
 * {@link StorageEngine#decr} 以及内部累加逻辑创建和更新。</p>
 *
 * <p>线程安全说明：这是不可变 record；并发下通过替换新对象来
 * 表达状态变化，本身可以安全共享。</p>
 *
 * <pre>{@code
 * CounterValue value = new CounterValue(1L, 0L, nowMs);
 * value = value.add(1L, nowMs + 1);
 * long result = value.value();
 * }</pre>
 *
 * @param value 当前计数值
 * @param expireAtMs 绝对过期时间；0 表示永不过期
 * @param lastAccessMs 最近访问时间
 */
public record CounterValue(long value, long expireAtMs, long lastAccessMs) implements StoredValue {
    /**
     * 返回计数器值的固定体积。
     *
     * @return {@link Long#BYTES}，也就是 8 字节
     */
    @Override
    public int sizeBytes() {
        return Long.BYTES;
    }

    /**
     * 复制当前计数器并刷新访问时间。
     *
     * @param lastAccessMs 新的最近访问时间
     * @return 新的 {@link CounterValue} 实例
     */
    @Override
    public CounterValue withLastAccessMs(long lastAccessMs) {
        return new CounterValue(value, expireAtMs, lastAccessMs);
    }

    /**
     * 复制当前计数器并刷新过期时间。
     *
     * @param expireAtMs 新的绝对过期时间；0 表示取消 TTL
     * @return 新的 {@link CounterValue} 实例
     */
    @Override
    public CounterValue withExpireAtMs(long expireAtMs) {
        return new CounterValue(value, expireAtMs, lastAccessMs);
    }

    /**
     * 对当前计数器执行增减操作。
     *
     * @param delta 本次要累加的增量；负数表示递减
     * @param nowMs 操作发生时的时间戳，用来刷新访问时间
     * @return 累加后的新计数器对象
     * @throws ArithmeticException 当 long 加法溢出时抛出
     * @implNote 使用 {@link Math#addExact(long, long)}，宁可显式报错，
     *     也不要默默绕回负数。
     */
    public CounterValue add(long delta, long nowMs) {
        return new CounterValue(Math.addExact(value, delta), expireAtMs, nowMs);
    }
}
