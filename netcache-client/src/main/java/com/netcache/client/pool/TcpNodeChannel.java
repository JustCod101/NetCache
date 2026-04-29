package com.netcache.client.pool;

import com.netcache.client.routing.ResponseRouter;
import com.netcache.protocol.Frame;
import com.netcache.protocol.codec.ProtocolDecoder;
import com.netcache.protocol.codec.ProtocolEncoder;
import com.netcache.protocol.command.Request;
import com.netcache.protocol.command.Response;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.util.concurrent.DefaultThreadFactory;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 基于 Netty TCP 的节点通道实现，像一条真正通往缓存节点的“高速公路”，负责把请求帧发出去并把响应帧接回来。
 * <p>
 * 之所以需要它，是因为客户端最终必须把抽象命令落到具体网络连接上；如果没有这个实现，连接池和路由器只能停留在接口层面无法真正通信。
 * <p>
 * 上游由 {@link NodeChannelFactory} 和 {@link ConnectionPool} 创建并持有；下游依赖 Netty 的 {@link Bootstrap}、编解码器与
 * {@link ResponseRouter} 完成网络 I/O 和请求-响应匹配。
 * <p>
 * 线程安全说明：该类可被并发发送请求。Netty Channel 支持多线程写入，而响应关联由线程安全的 {@link ResponseRouter} 维护；
 * 关闭操作应视为生命周期边界，不应与继续发送请求并行交错使用。
 * <p>
 * 典型用例：
 * <pre>{@code
 * TcpNodeChannel channel = TcpNodeChannel.connect("127.0.0.1:7001", connectTimeout, readTimeout, 0);
 * CompletableFuture<Response> future = channel.send(request);
 * Response response = future.join();
 * channel.close();
 * }</pre>
 */
final class TcpNodeChannel implements NodeChannel {
    private final Channel channel;
    private final EventLoopGroup group;
    private final ResponseRouter responseRouter;

    /**
     * 创建一个已经连通的 TCP 节点通道包装对象。
     *
     * @param channel 表示底层 Netty 通道，不能为空
     * @param group 表示驱动该通道的事件循环组，不能为空
     * @param responseRouter 表示负责按 requestId 分发响应的处理器，不能为空
     * @throws NullPointerException 当任一依赖为 {@code null} 时抛出
     * @implNote 构造器仅封装现成资源；真正的连接建立逻辑在静态 {@code connect} 工厂中完成。
     */
    private TcpNodeChannel(Channel channel, EventLoopGroup group, ResponseRouter responseRouter) {
        this.channel = Objects.requireNonNull(channel, "channel");
        this.group = Objects.requireNonNull(group, "group");
        this.responseRouter = Objects.requireNonNull(responseRouter, "responseRouter");
    }

    /**
     * 建立一条指向指定节点的 TCP 长连接。
     *
     * @param seed 表示目标节点地址，格式为 {@code host:port}
     * @param connectTimeout 表示连接超时时间
     * @param readTimeout 表示等待响应的超时时间
     * @param poolIndex 表示该连接在所属节点连接池中的序号
     * @return 返回已连接且已挂载编解码/响应路由器的 {@link TcpNodeChannel}
     * @throws IllegalStateException 当连接失败或连接过程中被中断时抛出
     * @implNote 每条连接都会创建独立的单线程 {@link EventLoopGroup} 与独立 {@link ResponseRouter}，避免不同连接间的状态互相污染。
     */
    static TcpNodeChannel connect(String seed, Duration connectTimeout, Duration readTimeout, int poolIndex) {
        InetSocketAddress address = parseSeed(seed);
        EventLoopGroup group = new NioEventLoopGroup(1, new DefaultThreadFactory("nc-client-io-" + poolIndex));
        ResponseRouter responseRouter = new ResponseRouter(readTimeout);
        try {
            Bootstrap bootstrap = new Bootstrap()
                    .group(group)
                    .channel(NioSocketChannel.class)
                    .handler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel channel) {
                            ChannelPipeline pipeline = channel.pipeline();
                            pipeline.addLast(new ProtocolDecoder());
                            pipeline.addLast(new ProtocolEncoder());
                            pipeline.addLast(responseRouter);
                        }
                    });
            ChannelFuture connectFuture = bootstrap.connect(address);
            boolean connected = connectFuture.await(connectTimeout.toMillis(), TimeUnit.MILLISECONDS);
            Channel channel = connectFuture.channel();
            if (!connected || !channel.isActive()) {
                throw new IllegalStateException("failed to connect to " + seed);
            }
            return new TcpNodeChannel(channel, group, responseRouter);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            group.shutdownGracefully();
            throw new IllegalStateException("interrupted connecting to " + seed, ex);
        } catch (RuntimeException ex) {
            group.shutdownGracefully();
            throw ex;
        }
    }

    @Override
    /**
     * 发送一条请求并返回与之关联的响应 future。
     *
     * @param request 表示待发送的协议请求，不能为空
     * @return 返回 future；完成后得到解码后的响应对象
     * @throws RuntimeException 当底层通道分配缓冲区或写入流程出现本地错误时可能抛出
     * @implNote 发送顺序是：编码负载 → 注册 requestId 对应 future → 异步写帧；若写失败，会主动让响应 future 失败，避免永久悬挂。
     */
    public CompletableFuture<Response> send(Request request) {
        ByteBuf payload = request.encodePayload(channel.alloc());
        CompletableFuture<Response> future = responseRouter.register(request.requestId());
        channel.writeAndFlush(Frame.request(request.requestId(), payload)).addListener(write -> {
            if (!write.isSuccess()) {
                responseRouter.fail(request.requestId(), write.cause());
            }
        });
        return future;
    }

    @Override
    /**
     * 关闭 TCP 通道及其事件循环资源。
     *
     * @implNote 当前实现先关闭 {@link Channel}，再优雅关闭 {@link EventLoopGroup}，适合客户端退出阶段使用。
     */
    public void close() {
        channel.close();
        group.shutdownGracefully();
    }

    /**
     * 解析种子节点地址字符串。
     *
     * @param seed 表示形如 {@code host:port} 的节点地址
     * @return 返回解析后的 {@link InetSocketAddress}
     * @throws IllegalArgumentException 当格式不合法或端口无法解析时抛出
     * @implNote 这里只做最基础的冒号切分，保持地址格式与构建器和示例程序一致。
     */
    private static InetSocketAddress parseSeed(String seed) {
        String[] parts = seed.split(":", 2);
        if (parts.length != 2) {
            throw new IllegalArgumentException("seed must be host:port");
        }
        return new InetSocketAddress(parts[0], Integer.parseInt(parts[1]));
    }
}
