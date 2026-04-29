package com.netcache.server.netty;

import com.netcache.protocol.codec.ProtocolDecoder;
import com.netcache.protocol.codec.ProtocolEncoder;
import com.netcache.server.ServerConfig;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.util.concurrent.DefaultThreadFactory;

import java.util.Objects;

/**
 * 负责组装 Netty 服务端启动器的建造者，像搭建流水线的工程师一样把线程组、通道类型和处理链拼装起来。
 *
 * <p>这个类解决的是网络基础设施装配问题：把 {@link ServerBootstrap} 创建细节集中封装，避免生命周期层
 * 直接接触大量 Netty 细节。如果没有它，启动代码会混入大量 pipeline 与线程池配置，难以阅读和复用。
 *
 * <p>上游通常是 {@code NodeLifecycle.start()}；下游依赖 {@link ServerConfig} 提供线程参数，依赖
 * {@link CommandDispatcher} 作为入站命令路由器。
 *
 * <p>线程安全说明：该类在构建阶段只读访问配置与 dispatcher，本身可安全复用；真正的并发执行发生在
 * {@link NioEventLoopGroup} 管理的 Netty event loop 中。
 *
 * <p>典型用例：
 * <pre>{@code
 * CommandDispatcher dispatcher = new CommandDispatcher(handlers);
 * ServerBootstrapBuilder builder = new ServerBootstrapBuilder(config, dispatcher);
 * ServerBootstrapBuilder.BuiltServerBootstrap built = builder.build();
 * built.bootstrap().bind(config.host(), config.port()).sync();
 * }</pre>
 *
 * <p>Netty Inbound 处理链：
 * <pre>
 * Socket.read() → LengthFieldBasedFrameDecoder → MagicValidator
 *               → ProtocolDecoder → CommandDispatcher → Handler
 *               → ProtocolEncoder → Socket.write()
 * </pre>
 */
public final class ServerBootstrapBuilder {
    /** 启动器装配所需的静态配置。 */
    private final ServerConfig config;
    /** 入站命令调度器，位于协议解码之后。 */
    private final CommandDispatcher dispatcher;

    /**
     * 创建 Netty 启动器建造者。
     *
     * @param config 服务端监听与线程参数
     * @param dispatcher 负责把请求路由到具体命令处理器的入站 handler
     * @throws NullPointerException 当任一参数为 {@code null} 时抛出
     * @implNote 构造器只保存引用，不提前创建线程组，避免无意义的资源占用。
     */
    public ServerBootstrapBuilder(ServerConfig config, CommandDispatcher dispatcher) {
        this.config = Objects.requireNonNull(config, "config");
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
    }

    /**
     * 构建包含线程组与 pipeline 配置的服务端启动器。
     *
     * @return 持有 {@link ServerBootstrap} 与底层事件循环组的聚合对象
     * @implNote 该方法一次性创建 boss/worker 线程组，并在 child pipeline 中依次放入协议解码器、协议编码器
     * 与命令调度器，保证连接建立后立即具备完整的收发能力。
     */
    public BuiltServerBootstrap build() {
        EventLoopGroup bossGroup = new NioEventLoopGroup(config.bossThreads(), new DefaultThreadFactory("nc-server-boss"));
        EventLoopGroup workerGroup = new NioEventLoopGroup(config.effectiveWorkerThreads(), new DefaultThreadFactory("nc-server-worker"));
        ServerBootstrap bootstrap = new ServerBootstrap()
                .group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel channel) {
                        /** 每个新连接独享的处理链。 */
                        ChannelPipeline pipeline = channel.pipeline();
                        pipeline.addLast(new ProtocolDecoder());
                        pipeline.addLast(new ProtocolEncoder());
                        pipeline.addLast(dispatcher);
                    }
                });
        return new BuiltServerBootstrap(bootstrap, bossGroup, workerGroup);
    }

    /**
     * 聚合已构建好的 bootstrap 与线程组，方便上层统一管理启动结果与关闭动作。
     *
     * <p>它解决的是“多个 Netty 资源需要一起返回”的问题；如果直接返回 {@link ServerBootstrap}，调用方就得额外
     * 保存线程组引用，停机时容易遗忘。
     *
     * <p>线程安全说明：record 本身不可变，但其内部引用的 Netty 对象具有生命周期状态，因此应由单一控制流使用。
     *
     * <p>典型用例：
     * <pre>{@code
     * BuiltServerBootstrap built = builder.build();
     * built.bootstrap().bind("0.0.0.0", 7001).sync();
     * built.shutdownGracefully();
     * }</pre>
     *
     * @param bootstrap 完整配置好的服务端启动器
     * @param bossGroup 接收连接的事件循环组
     * @param workerGroup 处理读写事件的事件循环组
     */
    public record BuiltServerBootstrap(ServerBootstrap bootstrap, EventLoopGroup bossGroup, EventLoopGroup workerGroup) {
        /**
         * 优雅关闭建造过程中创建的 Netty 线程组。
         *
         * @implNote 关闭顺序先 boss 后 worker，交由 Netty 自己处理剩余任务与线程退出节奏。
         */
        public void shutdownGracefully() {
            bossGroup.shutdownGracefully();
            workerGroup.shutdownGracefully();
        }
    }
}
