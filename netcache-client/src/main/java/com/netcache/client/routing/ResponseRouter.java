package com.netcache.client.routing;

import com.netcache.protocol.Frame;
import com.netcache.protocol.command.Response;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 响应路由器，像快递柜取件码系统一样，按 requestId 把网络响应精确投递给等待中的请求 future。
 * <p>
 * 之所以需要它，是因为同一条连接上会并发飞行多条请求；如果没有这层映射，客户端就无法知道某个响应该交给哪个调用方。
 * <p>
 * 上游由 {@link com.netcache.client.pool.TcpNodeChannel} 在发送前注册请求，并在写失败时回调失败；下游作为 Netty 入站处理器接收
 * {@link Frame} 并解码为 {@link Response}。
 * <p>
 * 线程安全说明：该类支持并发使用。挂起请求表使用 {@link ConcurrentHashMap}，既能被业务线程注册，也能被 Netty I/O 线程完成或移除。
 * <p>
 * 典型用例：
 * <pre>{@code
 * CompletableFuture<Response> future = responseRouter.register(requestId);
 * channel.writeAndFlush(frame);
 * Response response = future.join();
 * }</pre>
 */
public final class ResponseRouter extends SimpleChannelInboundHandler<Frame> {
    /** 保存所有尚未收到响应的请求 future，key 为 requestId。 */
    private final ConcurrentHashMap<Long, CompletableFuture<Response>> pending = new ConcurrentHashMap<>();
    /** 读取超时阈值，超时后会自动将对应请求从挂起表中移除。 */
    private final Duration readTimeout;

    /**
     * 创建响应路由器。
     *
     * @param readTimeout 表示等待节点响应的超时时间，不能为空
     * @throws NullPointerException 当 {@code readTimeout} 为 {@code null} 时抛出
     * @implNote 超时控制依赖 {@link CompletableFuture#orTimeout(long, java.util.concurrent.TimeUnit)}，无需额外调度线程。
     */
    public ResponseRouter(Duration readTimeout) {
        this.readTimeout = Objects.requireNonNull(readTimeout, "readTimeout");
    }

    /**
     * 为一个即将发送的请求注册响应 future。
     *
     * @param requestId 表示请求唯一标识
     * @return 返回一个尚未完成的 future，待收到响应或错误时完成
     * @throws IllegalStateException 当前实现不主动检测重复 requestId，但重复注册会覆盖旧值并带来响应错配风险
     * @implNote future 注册后会立即附加超时回调，一旦超时或异常完成，就会把对应条目从挂起表移除。
     */
    public CompletableFuture<Response> register(long requestId) {
        CompletableFuture<Response> future = new CompletableFuture<>();
        pending.put(requestId, future);
        future.orTimeout(readTimeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)
                .whenComplete((ignored, error) -> {
                    if (error != null) {
                        pending.remove(requestId);
                    }
                });
        return future;
    }

    /**
     * 主动让某个挂起请求失败。
     *
     * @param requestId 表示要失败的请求 ID
     * @param cause 表示失败原因
     * @implNote 该方法主要由发送失败回调触发，确保请求即使没进网络，也不会让调用方一直等待。
     */
    public void fail(long requestId, Throwable cause) {
        CompletableFuture<Response> future = pending.remove(requestId);
        if (future != null) {
            future.completeExceptionally(cause);
        }
    }

    @Override
    /**
     * 处理入站响应帧并完成对应 future。
     *
     * @param ctx 表示当前 Netty 处理上下文
     * @param frame 表示已解码出的协议帧
     * @implNote 处理顺序是：按 requestId 取出挂起 future → 解码响应 → 完成 future；无论是否匹配到 future，最后都会释放帧资源。
     */
    protected void channelRead0(ChannelHandlerContext ctx, Frame frame) {
        try {
            CompletableFuture<Response> future = pending.remove(frame.requestId());
            if (future != null) {
                future.complete(Response.decodePayload(frame.requestId(), frame.payload().slice()));
            }
        } finally {
            frame.close();
        }
    }
}
