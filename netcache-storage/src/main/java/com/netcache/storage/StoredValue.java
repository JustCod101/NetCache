package com.netcache.storage;

/**
 * 存储层里的统一值模型，相当于数据的身份证。
 * 它把字符串值、计数器值都收拢到同一套协议里。
 * 没有这层抽象，{@link StorageEngine} 就得为每种值类型
 * 分叉处理过期、访问时间和体积统计，代码会很散。
 *
 * <p>上游主要是 {@link StorageEngine}，下游实现是
 * {@link StringValue} 和 {@link CounterValue}。</p>
 *
 * <p>线程安全说明：接口本身无状态；两个实现都是不可变对象，
 * 可以安全地在并发场景中共享。</p>
 *
 * <pre>{@code
 * StoredValue value = new StringValue("v".getBytes(), 0L, nowMs);
 * if (!value.isExpired(nowMs)) {
 *     int bytes = value.sizeBytes();
 * }
 * }</pre>
 */
public sealed interface StoredValue permits StringValue, CounterValue {
    /**
     * 返回绝对过期时间。
     *
     * @return 过期时间戳，单位毫秒；0 表示永不过期
     */
    long expireAtMs();

    /**
     * 返回最近一次访问时间。
     *
     * @return 最近访问的毫秒时间戳
     */
    long lastAccessMs();

    /**
     * 返回该值大致占用的数据体积。
     * 这里关注的是值本身，不含 Map 节点等额外开销。
     *
     * @return 值载荷的字节数
     */
    int sizeBytes();

    /**
     * 复制一份值对象，并更新最近访问时间。
     *
     * @param lastAccessMs 新的访问时间，通常来自当前时钟
     * @return 带有新访问时间的值对象
     */
    StoredValue withLastAccessMs(long lastAccessMs);

    /**
     * 复制一份值对象，并更新绝对过期时间。
     *
     * @param expireAtMs 新的过期时间；0 表示取消过期
     * @return 带有新过期时间的值对象
     */
    StoredValue withExpireAtMs(long expireAtMs);

    /**
     * 判断值在给定时间点是否已经失效。
     *
     * @param nowMs 当前时间的毫秒值
     * @return {@code true} 表示已经过期；永久键始终返回
     *     {@code false}
     * @implNote 这里把 0 视为“永不过期”，避免每次判断都得
     *     引入额外布尔位。
     */
    default boolean isExpired(long nowMs) {
        return expireAtMs() > 0 && expireAtMs() <= nowMs;
    }
}
