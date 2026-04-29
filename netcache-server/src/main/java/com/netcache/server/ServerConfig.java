package com.netcache.server;

import java.util.Objects;

/**
 * 封装服务端启动参数的只读配置载体，像配电箱铭牌一样定义节点该以什么规格运行。
 *
 * <p>这个记录类解决配置收口问题：把主机地址、端口以及 Netty 线程数集中在一个不可变对象里。
 * 如果没有它，调用方只能分散传递多个原始参数，既容易出错，也不方便做统一校验和默认值管理。
 *
 * <p>上游通常是 {@link NetCacheServer} 或测试代码；下游是生命周期管理和 Netty 启动器，例如
 * {@code NodeLifecycle}、{@code ServerBootstrapBuilder}。
 *
 * <p>线程安全说明：作为不可变 record，该类天然线程安全，可在多个线程之间安全共享。
 *
 * <p>典型用例：
 * <pre>{@code
 * ServerConfig config = ServerConfig.fromSystemProperties();
 * int port = config.port();
 * int workerThreads = config.effectiveWorkerThreads();
 * }</pre>
 *
 * @param host 服务监听地址
 * @param port 服务监听端口，取值范围为 1..65535
 * @param bossThreads Netty boss 线程数，用于接收连接
 * @param workerThreads Netty worker 线程数，0 表示按 CPU 数自动推导
 */
public record ServerConfig(String host, int port, int bossThreads, int workerThreads) {
    /**
     * 创建并校验服务端配置。
     *
     * @implNote record 的紧凑构造器承担全部参数校验职责，确保实例一旦创建就处于可运行状态。
     */
    public ServerConfig {
        Objects.requireNonNull(host, "host");
        if (port <= 0 || port > 65535) {
            throw new IllegalArgumentException("port must be 1..65535");
        }
        if (bossThreads <= 0) {
            throw new IllegalArgumentException("bossThreads must be positive");
        }
        if (workerThreads < 0) {
            throw new IllegalArgumentException("workerThreads must be zero or positive");
        }
    }

    /**
     * 返回适合本地开发与默认部署的基础配置。
     *
     * @return 默认配置，监听全部网卡并使用 7001 端口
     * @implNote 默认 worker 线程数设为 0，后续通过 {@link #effectiveWorkerThreads()} 延迟解析为 CPU 相关值。
     */
    public static ServerConfig defaults() {
        return new ServerConfig("0.0.0.0", 7001, 1, 0);
    }

    /**
     * 从 JVM 系统属性构建服务端配置。
     *
     * @return 合并默认值后的配置对象
     * @throws IllegalArgumentException 当系统属性中的数值超出允许范围时抛出
     * @implNote 先取 {@link #defaults()} 作为兜底，再分别读取 {@code netcache.*} 属性；这样既保留了默认行为，
     * 也让调用方只覆盖需要修改的项。
     */
    public static ServerConfig fromSystemProperties() {
        ServerConfig defaults = defaults();
        return new ServerConfig(
                System.getProperty("netcache.host", defaults.host()),
                Integer.getInteger("netcache.port", defaults.port()),
                Integer.getInteger("netcache.bossThreads", defaults.bossThreads()),
                Integer.getInteger("netcache.workerThreads", defaults.workerThreads()));
    }

    /**
     * 计算最终应该使用的 worker 线程数。
     *
     * @return 显式配置的 worker 线程数；若配置为 0，则返回 {@code CPU * 2}
     * @implNote 使用延迟计算而不是在构造器里写死结果，可以保留“0 代表自动模式”的原始语义，便于日志与调试。
     */
    public int effectiveWorkerThreads() {
        return workerThreads == 0 ? Runtime.getRuntime().availableProcessors() * 2 : workerThreads;
    }
}
