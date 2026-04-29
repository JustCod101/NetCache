package com.netcache.client;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * NetCache 客户端统一门面，像一把面向调用方的“遥控器”，把底层路由、连接和协议细节都藏在按键后面。
 * <p>
 * 之所以需要这个接口，是为了给业务代码提供稳定的同步/异步 KV 操作入口；如果没有它，调用方就必须直接理解请求构造、节点路由和响应解码，
 * 使用门槛与耦合度都会明显升高。
 * <p>
 * 上游通常由业务服务、脚本或示例程序调用；典型实现会依赖 {@link com.netcache.client.routing.RequestRouter}、连接池和重试策略来完成真正的网络交互。
 * <p>
 * 线程安全说明：接口本身不持有状态，线程安全取决于具体实现；默认实现支持多线程并发复用。
 * <p>
 * 典型用例：
 * <pre>{@code
 * try (NetCacheClient client = NetCacheClient.builder()
 *         .seeds("127.0.0.1:7001")
 *         .build()) {
 *     client.set("hello".getBytes(), "world".getBytes());
 *     byte[] value = client.get("hello".getBytes());
 * }
 * }</pre>
 */
public interface NetCacheClient extends AutoCloseable {
    /**
     * 读取指定键对应的值。
     *
     * @param key 表示缓存键的字节数组，不能为空
     * @return 返回命中的值字节数组；如果键不存在则返回 {@code null}
     * @throws java.util.concurrent.CompletionException 当底层请求超时、路由失败或服务端返回错误时抛出
     * @implNote 默认实现会先复制入参，再经由异步请求发送到目标节点，最后在同步包装层等待结果返回。
     */
    byte[] get(byte[] key);

    /**
     * 写入一个无 TTL 的键值对。
     *
     * @param key 表示缓存键的字节数组，不能为空
     * @param value 表示要保存的值字节数组，不能为空
     * @throws java.util.concurrent.CompletionException 当底层请求失败、重试耗尽或服务端拒绝写入时抛出
     * @implNote 默认实现会把该调用转换为异步 SET，再通过同步等待桥接回调用线程。
     */
    void set(byte[] key, byte[] value);

    /**
     * 写入一个带过期时间的键值对。
     *
     * @param key 表示缓存键的字节数组，不能为空
     * @param value 表示要保存的值字节数组，不能为空
     * @param ttl 表示键的存活时长；零值或负值通常会被实现视为“不附带 TTL”
     * @throws java.util.concurrent.CompletionException 当请求发送失败、重试后仍未成功或服务端返回错误时抛出
     * @implNote 默认实现会将 TTL 转为毫秒文本参数追加到协议请求尾部。
     */
    void set(byte[] key, byte[] value, Duration ttl);

    /**
     * 对指定键执行自增操作。
     *
     * @param key 表示要自增的键，不能为空
     * @return 返回自增后的最新数值
     * @throws java.util.concurrent.CompletionException 当键值不可解析为整数、请求失败或服务端报错时抛出
     * @implNote 默认实现依赖服务端返回 {@code INT64} 类型结果，再由客户端解码为 {@code long}。
     */
    long incr(byte[] key);

    /**
     * 删除指定键。
     *
     * @param key 表示要删除的键，不能为空
     * @return 返回 {@code true} 表示至少删除了一个键，返回 {@code false} 表示键原本不存在
     * @throws java.util.concurrent.CompletionException 当路由、网络或服务端处理失败时抛出
     * @implNote 默认实现会把服务端返回的整型删除计数转换成布尔语义。
     */
    boolean del(byte[] key);

    /**
     * 更新指定键的过期时间。
     *
     * @param key 表示要更新 TTL 的键，不能为空
     * @param ttl 表示新的存活时长，不能为空
     * @return 返回 {@code true} 表示 TTL 更新成功，返回 {@code false} 表示键不存在或未能更新
     * @throws java.util.concurrent.CompletionException 当请求失败、TTL 非法或服务端返回错误时抛出
     * @implNote 默认实现会将 TTL 统一编码为毫秒值发送给服务端。
     */
    boolean expire(byte[] key, Duration ttl);

    /**
     * 异步读取指定键对应的值。
     *
     * @param key 表示缓存键的字节数组，不能为空
     * @return 返回一个未来对象；完成后得到命中值，若键不存在则结果为 {@code null}
     * @throws java.util.concurrent.CompletionException 当 future 被同步等待时，底层错误会以包装异常形式抛出
     * @implNote 默认实现直接返回底层异步请求 future，让调用方自行决定编排方式。
     */
    CompletableFuture<byte[]> getAsync(byte[] key);

    /**
     * 异步写入一个无 TTL 的键值对。
     *
     * @param key 表示缓存键的字节数组，不能为空
     * @param value 表示要保存的值字节数组，不能为空
     * @return 返回一个未来对象；完成即表示写入成功
     * @throws java.util.concurrent.CompletionException 当 future 被等待时，网络或服务端错误会透传出来
     * @implNote 默认实现通常委托给带 TTL 的重载，并用零时长表示“不附带过期时间”。
     */
    CompletableFuture<Void> setAsync(byte[] key, byte[] value);

    /**
     * 异步写入一个带过期时间的键值对。
     *
     * @param key 表示缓存键的字节数组，不能为空
     * @param value 表示要保存的值字节数组，不能为空
     * @param ttl 表示键的存活时长
     * @return 返回一个未来对象；完成即表示写入与 TTL 设置成功
     * @throws java.util.concurrent.CompletionException 当 future 被等待时，底层路由、网络或服务端错误会透传出来
     * @implNote 默认实现只在 TTL 为正值时编码过期参数，避免无意义地发送 0 或负数。
     */
    CompletableFuture<Void> setAsync(byte[] key, byte[] value, Duration ttl);

    /**
     * 异步对指定键执行自增操作。
     *
     * @param key 表示要自增的键，不能为空
     * @return 返回一个未来对象；完成后得到自增后的最新数值
     * @throws java.util.concurrent.CompletionException 当 future 被等待时，协议类型错误或服务端错误会透传出来
     * @implNote 默认实现会对响应类型做显式校验，确保只接收 64 位整数结果。
     */
    CompletableFuture<Long> incrAsync(byte[] key);

    /**
     * 异步删除指定键。
     *
     * @param key 表示要删除的键，不能为空
     * @return 返回一个未来对象；完成后得到删除是否生效的布尔结果
     * @throws java.util.concurrent.CompletionException 当 future 被等待时，底层发送失败或服务端异常会透传出来
     * @implNote 默认实现把整型删除计数压缩成布尔值，方便业务侧直接判断。
     */
    CompletableFuture<Boolean> delAsync(byte[] key);

    /**
     * 异步更新指定键的过期时间。
     *
     * @param key 表示要更新 TTL 的键，不能为空
     * @param ttl 表示新的存活时长，不能为空
     * @return 返回一个未来对象；完成后得到 TTL 是否更新成功
     * @throws java.util.concurrent.CompletionException 当 future 被等待时，超时、网络或服务端错误会透传出来
     * @implNote 默认实现会将 TTL 转成毫秒文本并与键一起发给服务端执行 EXPIRE。
     */
    CompletableFuture<Boolean> expireAsync(byte[] key, Duration ttl);

    /**
     * 关闭客户端并释放底层资源。
     *
     * @throws RuntimeException 当底层连接池或网络资源释放失败时可能抛出运行时异常
     * @implNote 默认实现会级联关闭路由器、连接池以及每个节点连接，调用后不应继续复用该客户端。
     */
    @Override
    void close();

    /**
     * 创建一个新的客户端构建器。
     *
     * @return 返回一个带默认参数的 {@link ClientBuilder}
     * @implNote 这是一个轻量工厂方法，每次调用都会返回全新的构建器实例，互不共享可变状态。
     */
    static ClientBuilder builder() {
        return new ClientBuilder();
    }
}
