package com.netcache.server.lifecycle;

import com.netcache.server.ServerConfig;
import com.netcache.server.handler.HandlerRegistry;
import com.netcache.server.netty.CommandDispatcher;
import com.netcache.server.netty.ServerBootstrapBuilder;
import com.netcache.storage.StorageEngine;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;

import java.util.Objects;

/**
 * 管理单个 NetCache 节点从启动到停止全过程的生命周期协调器，像舞台监督一样串起演员、灯光和收场流程。
 *
 * <p>这个类解决的是资源编排问题：把存储引擎、命令分发器、Netty bootstrap 与实际绑定的 channel
 * 放在同一个对象中统一管理。如果没有它，启动和停止顺序会散落在多个调用点，容易造成端口未释放或存储未关闭。
 *
 * <p>上游通常是 {@code NetCacheServer.main} 或集成测试；下游依赖 {@link StorageEngine}、
 * {@link HandlerRegistry}、{@link CommandDispatcher} 与 {@link ServerBootstrapBuilder}。
 *
 * <p>线程安全说明：该类持有可变生命周期状态（{@code built}、{@code channel}），不面向多线程并发调用；
 * 约定由单个控制线程顺序执行启动与停止，而网络读写并发由 Netty event loop 负责。
 *
 * <p>典型用例：
 * <pre>{@code
 * NodeLifecycle lifecycle = new NodeLifecycle(ServerConfig.defaults());
 * lifecycle.start();
 * lifecycle.closeFuture().sync();
 * lifecycle.stop();
 * }</pre>
 */
public final class NodeLifecycle {
    /** 节点监听地址与线程模型配置。 */
    private final ServerConfig config;
    /** 提供 KV 数据读写能力的本地存储引擎。 */
    private final StorageEngine storageEngine;
    /** 保存已构建好的 Netty 启动器及线程组，便于停机时回收资源。 */
    private ServerBootstrapBuilder.BuiltServerBootstrap built;
    /** 启动后绑定到本地端口的服务端 channel。 */
    private Channel channel;

    /**
     * 使用默认存储引擎创建节点生命周期。
     *
     * @param config 节点运行配置
     * @implNote 这是生产入口的便捷构造器，统一委托到带 {@link StorageEngine} 的构造器，避免初始化逻辑分叉。
     */
    public NodeLifecycle(ServerConfig config) {
        this(config, new StorageEngine());
    }

    /**
     * 使用显式存储引擎创建节点生命周期。
     *
     * @param config 节点运行配置
     * @param storageEngine 用于承载命令执行结果的存储引擎
     * @throws NullPointerException 当任一参数为 {@code null} 时抛出
     * @implNote 该构造器主要服务于测试注入与受控装配，确保生命周期对象对关键依赖保持显式声明。
     */
    public NodeLifecycle(ServerConfig config, StorageEngine storageEngine) {
        this.config = Objects.requireNonNull(config, "config");
        this.storageEngine = Objects.requireNonNull(storageEngine, "storageEngine");
    }

    /**
     * 启动节点并绑定监听端口。
     *
     * @throws InterruptedException 当 Netty 绑定端口时当前线程被中断时抛出
     * @implNote 启动顺序固定为：创建 handler 映射 → 创建 dispatcher → 构建 bootstrap → 绑定 channel，
     * 这样可以保证对外提供服务前，整条命令处理链已经准备完毕。
     */
    public void start() throws InterruptedException {
        CommandDispatcher dispatcher = new CommandDispatcher(HandlerRegistry.singleNode(storageEngine));
        built = new ServerBootstrapBuilder(config, dispatcher).build();
        channel = built.bootstrap().bind(config.host(), config.port()).sync().channel();
    }

    /**
     * 返回服务端 channel 的关闭通知 future。
     *
     * @return 可用于阻塞等待节点关闭的 future
     * @throws IllegalStateException 当节点尚未启动、没有可观察的 channel 时抛出
     * @implNote 这里显式拒绝“未启动就等待关闭”的误用，便于调用方尽早发现生命周期顺序错误。
     */
    public ChannelFuture closeFuture() {
        if (channel == null) {
            throw new IllegalStateException("server not started");
        }
        return channel.closeFuture();
    }

    /**
     * 停止节点并尽量优雅地回收底层资源。
     *
     * @implNote 停机顺序为先关闭 channel，再关闭存储引擎，最后关闭 boss/worker 线程组；
     * 各步骤都做空值保护，以兼容部分初始化后失败或重复 stop 的场景。
     */
    public void stop() {
        if (channel != null) {
            channel.close();
        }
        storageEngine.close();
        if (built != null) {
            built.shutdownGracefully();
        }
    }
}
