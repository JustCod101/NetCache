package com.netcache.client;

import com.netcache.protocol.OpCode;
import com.netcache.protocol.ResultType;
import com.netcache.protocol.Status;
import com.netcache.protocol.command.Request;
import com.netcache.protocol.command.Response;
import com.netcache.client.retry.RetryPolicy;
import com.netcache.client.routing.RequestRouter;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * {@link NetCacheClient} 的默认实现，像总调度台一样把请求组装、路由发送、结果解码和失败重试串成一条完整流水线。
 * <p>
 * 之所以需要它，是因为业务侧只想发起 KV 操作，不应该自己手写协议编码、节点选择和异常恢复；如果没有这层实现，
 * 每个调用方都得重复维护一套网络交互细节。
 * <p>
 * 上游主要由 {@link ClientBuilder} 构造并返回给业务代码；下游依赖 {@link RequestRouter} 负责投递请求，依赖 {@link RetryPolicy}
 * 负责指数退避重试。
 * <p>
 * 线程安全说明：该类可被多线程安全共享。实例本身只持有不可变依赖和一个 {@link AtomicLong} 请求号生成器，
 * 每次调用都通过局部变量构造请求，不共享可变命令状态。
 * <p>
 * 典型用例：
 * <pre>{@code
 * NetCacheClient client = NetCacheClient.builder()
 *         .seeds("127.0.0.1:7001")
 *         .maxRetries(3)
 *         .build();
 * client.set("k".getBytes(), "v".getBytes());
 * }</pre>
 */
public final class DefaultNetCacheClient implements NetCacheClient {
    private final RequestRouter requestRouter;
    private final RetryPolicy retryPolicy;
    /** 以原子递增方式生成请求 ID，确保并发请求也能拿到唯一关联键。 */
    private final AtomicLong requestIds = new AtomicLong(1);

    /**
     * 创建默认客户端实现。
     *
     * @param requestRouter 负责把请求投递到目标节点的路由器，不能为空
     * @param retryPolicy 负责失败重试与退避节奏的策略，不能为空
     * @throws NullPointerException 当任一依赖为 {@code null} 时抛出
     * @implNote 构造阶段只保存依赖，不主动建立新连接；连接创建通常已由构建器和连接池完成。
     */
    public DefaultNetCacheClient(RequestRouter requestRouter, RetryPolicy retryPolicy) {
        this.requestRouter = Objects.requireNonNull(requestRouter, "requestRouter");
        this.retryPolicy = Objects.requireNonNull(retryPolicy, "retryPolicy");
    }

    @Override
    /**
     * 同步读取指定键对应的值。
     *
     * @param key 表示缓存键的字节数组，不能为空
     * @return 返回命中的值；键不存在时返回 {@code null}
     * @throws CompletionException 当异步请求超时、路由失败或响应校验失败时抛出
     * @implNote 该方法只是对 {@link #getAsync(byte[])} 的同步包装，统一复用 join 超时等待逻辑。
     */
    public byte[] get(byte[] key) {
        return join(getAsync(key));
    }

    @Override
    /**
     * 同步写入一个无 TTL 的键值对。
     *
     * @param key 表示缓存键的字节数组，不能为空
     * @param value 表示要写入的值字节数组，不能为空
     * @throws CompletionException 当异步写入失败、重试耗尽或服务端返回错误时抛出
     * @implNote 该方法委托给异步写入重载，并在调用线程阻塞等待完成。
     */
    public void set(byte[] key, byte[] value) {
        join(setAsync(key, value));
    }

    @Override
    /**
     * 同步写入一个带过期时间的键值对。
     *
     * @param key 表示缓存键的字节数组，不能为空
     * @param value 表示要写入的值字节数组，不能为空
     * @param ttl 表示键的过期时间
     * @throws CompletionException 当异步写入失败、服务端拒绝或响应异常时抛出
     * @implNote 具体 TTL 编码规则由异步实现负责，这里只做同步桥接。
     */
    public void set(byte[] key, byte[] value, Duration ttl) {
        join(setAsync(key, value, ttl));
    }

    @Override
    /**
     * 同步执行自增命令。
     *
     * @param key 表示要自增的键，不能为空
     * @return 返回自增后的最新值
     * @throws CompletionException 当服务端返回非整数结果或请求链路失败时抛出
     * @implNote 该方法通过等待异步 future 完成来复用统一的错误处理和超时机制。
     */
    public long incr(byte[] key) {
        return join(incrAsync(key));
    }

    @Override
    /**
     * 同步删除指定键。
     *
     * @param key 表示要删除的键，不能为空
     * @return 返回 {@code true} 表示删除成功，返回 {@code false} 表示键不存在
     * @throws CompletionException 当底层发送或响应解析失败时抛出
     * @implNote 删除命令的布尔语义由异步实现根据服务端返回的计数结果转换而来。
     */
    public boolean del(byte[] key) {
        return join(delAsync(key));
    }

    @Override
    /**
     * 同步更新键的过期时间。
     *
     * @param key 表示要更新 TTL 的键，不能为空
     * @param ttl 表示新的过期时间，不能为空
     * @return 返回 {@code true} 表示更新成功，返回 {@code false} 表示键不存在或未更新
     * @throws CompletionException 当请求失败、响应类型错误或服务端返回异常时抛出
     * @implNote 该方法仍旧复用异步命令发送链路，避免同步和异步逻辑分叉。
     */
    public boolean expire(byte[] key, Duration ttl) {
        return join(expireAsync(key, ttl));
    }

    @Override
    /**
     * 异步发送 GET 请求。
     *
     * @param key 表示缓存键的字节数组，不能为空
     * @return 返回 future；完成后得到值字节数组，若服务端返回 NIL 则结果为 {@code null}
     * @throws CompletionException 当 future 被外部等待时，路由或服务端错误会以包装异常形式暴露
     * @implNote 实现会先复制键，避免调用方后续修改原始数组造成请求内容漂移。
     */
    public CompletableFuture<byte[]> getAsync(byte[] key) {
        return send(OpCode.GET, List.of(copy(key))).thenApply(response -> response.status() == Status.NIL ? null : bytes(response));
    }

    @Override
    /**
     * 异步发送无 TTL 的 SET 请求。
     *
     * @param key 表示缓存键的字节数组，不能为空
     * @param value 表示要写入的值字节数组，不能为空
     * @return 返回 future；完成即表示写入成功
     * @throws CompletionException 当 future 被等待时，底层网络或服务端错误会透传出来
     * @implNote 通过将 TTL 固定为 {@link Duration#ZERO} 来复用带 TTL 的发送逻辑，减少重复代码。
     */
    public CompletableFuture<Void> setAsync(byte[] key, byte[] value) {
        return setAsync(key, value, Duration.ZERO);
    }

    @Override
    /**
     * 异步发送带 TTL 的 SET 请求。
     *
     * @param key 表示缓存键的字节数组，不能为空
     * @param value 表示要写入的值字节数组，不能为空
     * @param ttl 表示键的过期时间
     * @return 返回 future；完成即表示服务端已确认写入
     * @throws NullPointerException 当 {@code key} 或 {@code value} 为 {@code null} 时由复制逻辑触发
     * @throws CompletionException 当 future 被等待时，路由失败或服务端返回错误会透传出来
     * @implNote 只有 TTL 为正值时才会将毫秒参数附加到请求后部，零值和负值会被视作“不带 TTL”。
     */
    public CompletableFuture<Void> setAsync(byte[] key, byte[] value, Duration ttl) {
        List<byte[]> args = new ArrayList<>();
        args.add(copy(key));
        args.add(copy(value));
        if (!ttl.isZero() && !ttl.isNegative()) {
            args.add(Long.toString(ttl.toMillis()).getBytes(StandardCharsets.UTF_8));
        }
        return send(OpCode.SET, args).thenApply(DefaultNetCacheClient::voidResult);
    }

    @Override
    /**
     * 异步发送 INCR 请求。
     *
     * @param key 表示要自增的键，不能为空
     * @return 返回 future；完成后得到自增后的数值
     * @throws CompletionException 当 future 被等待时，协议类型不匹配或服务端错误会透传出来
     * @implNote 返回结果会经过 {@link #int64(Response)} 做严格类型校验，避免误把其他响应类型当作整数读取。
     */
    public CompletableFuture<Long> incrAsync(byte[] key) {
        return send(OpCode.INCR, List.of(copy(key))).thenApply(DefaultNetCacheClient::int64);
    }

    @Override
    /**
     * 异步发送 DEL 请求。
     *
     * @param key 表示要删除的键，不能为空
     * @return 返回 future；完成后用布尔值表示是否真的删除了键
     * @throws CompletionException 当 future 被等待时，网络错误或服务端错误会透传出来
     * @implNote 服务端返回的是整数删除条数，这里统一转换为业务侧更容易使用的布尔语义。
     */
    public CompletableFuture<Boolean> delAsync(byte[] key) {
        return send(OpCode.DEL, List.of(copy(key))).thenApply(response -> int64(response) == 1L);
    }

    @Override
    /**
     * 异步发送 EXPIRE 请求。
     *
     * @param key 表示要更新 TTL 的键，不能为空
     * @param ttl 表示新的过期时间，不能为空
     * @return 返回 future；完成后用布尔值表示 TTL 是否更新成功
     * @throws NullPointerException 当 {@code ttl} 为 {@code null} 时抛出
     * @throws CompletionException 当 future 被等待时，底层请求失败或服务端报错会透传出来
     * @implNote TTL 会先转为 UTF-8 编码的毫秒文本，以保持和服务端命令协议一致。
     */
    public CompletableFuture<Boolean> expireAsync(byte[] key, Duration ttl) {
        Objects.requireNonNull(ttl, "ttl");
        return send(OpCode.EXPIRE, List.of(copy(key), Long.toString(ttl.toMillis()).getBytes(StandardCharsets.UTF_8)))
                .thenApply(response -> int64(response) == 1L);
    }

    @Override
    /**
     * 关闭客户端并释放底层网络资源。
     *
     * @implNote 当前实现会直接关闭请求路由器，而路由器会进一步关闭连接池与所有节点连接。
     */
    public void close() {
        requestRouter.close();
    }

    /**
     * 发送协议请求并按配置应用重试策略。
     *
     * @param opCode 表示要执行的协议操作码
     * @param args 表示已经按协议顺序编码好的参数列表
     * @return 返回 future；完成后得到原始协议响应
     * @throws CompletionException 当重试耗尽后仍失败时，最终异常会保存在返回的 future 中
     * @implNote 每次发送前都会生成新的请求 ID，确保重试后的请求也能独立关联响应。
     */
    private CompletableFuture<Response> send(OpCode opCode, List<byte[]> args) {
        Request request = new Request(opCode, args, requestIds.getAndIncrement());
        return retryPolicy.execute(() -> requestRouter.route(request));
    }

    /**
     * 校验响应成功并将其映射为空结果。
     *
     * @param response 表示服务端返回的响应对象
     * @return 固定返回 {@code null}，用于适配 {@code CompletableFuture<Void>}
     * @throws IllegalStateException 当响应状态不是 OK 时抛出
     * @implNote 该辅助方法集中处理无返回值命令的状态校验，避免多个调用点重复样板代码。
     */
    private static Void voidResult(Response response) {
        ensureOk(response);
        return null;
    }

    /**
     * 提取字节或字符串类型的响应体。
     *
     * @param response 表示服务端返回的响应对象
     * @return 返回响应体原始字节数组
     * @throws IllegalStateException 当状态非 OK 或返回类型不是字节/字符串时抛出
     * @implNote 字符串响应沿用原始字节数组返回，由更外层决定如何按字符集解释。
     */
    private static byte[] bytes(Response response) {
        ensureOk(response);
        if (response.type() != ResultType.BYTES && response.type() != ResultType.STRING) {
            throw new IllegalStateException("expected byte response but got " + response.type());
        }
        return response.body();
    }

    /**
     * 提取 64 位整数类型的响应体。
     *
     * @param response 表示服务端返回的响应对象
     * @return 返回解码后的 {@code long} 值
     * @throws IllegalStateException 当状态非 OK 或返回类型不是 {@code INT64} 时抛出
     * @implNote 使用 {@link ByteBuffer} 按协议定义的大端顺序读取 8 字节整数。
     */
    private static long int64(Response response) {
        ensureOk(response);
        if (response.type() != ResultType.INT64) {
            throw new IllegalStateException("expected int64 response but got " + response.type());
        }
        return ByteBuffer.wrap(response.body()).getLong();
    }

    /**
     * 校验响应状态是否成功。
     *
     * @param response 表示服务端返回的响应对象
     * @throws IllegalStateException 当服务端返回非 OK 状态时抛出，并把响应体当作错误文本输出
     * @implNote 该方法是客户端错误语义的第一道关口，任何业务解码前都必须先过这一层。
     */
    private static void ensureOk(Response response) {
        if (response.status() != Status.OK) {
            throw new IllegalStateException(new String(response.body(), StandardCharsets.UTF_8));
        }
    }

    /**
     * 复制调用方传入的字节数组。
     *
     * @param value 表示原始字节数组，不能为空
     * @return 返回克隆后的独立副本
     * @throws NullPointerException 当 {@code value} 为 {@code null} 时抛出
     * @implNote 复制是为了隔离调用方对原数组的后续修改，避免异步发送阶段读到被篡改的数据。
     */
    private static byte[] copy(byte[] value) {
        return Objects.requireNonNull(value, "value").clone();
    }

    /**
     * 在固定超时时间内等待 future 完成并提取结果。
     *
     * @param future 表示要等待的异步结果
     * @param <T> 表示结果类型
     * @return 返回 future 成功完成后的值
     * @throws CompletionException 当等待超时、线程中断或 future 异常完成时抛出
     * @implNote 当前同步 API 统一采用 30 秒上限，避免调用线程无限阻塞。
     */
    private static <T> T join(CompletableFuture<T> future) {
        try {
            return future.get(30, TimeUnit.SECONDS);
        } catch (Exception ex) {
            throw new CompletionException(ex);
        }
    }
}
