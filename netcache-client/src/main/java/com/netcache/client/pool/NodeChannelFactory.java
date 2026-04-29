package com.netcache.client.pool;

import java.time.Duration;

/**
 * 节点通道工厂，像连接池背后的“造车厂”，负责按节点地址和连接序号生产具体的 {@link NodeChannel}。
 * <p>
 * 之所以需要它，是为了让连接创建策略可插拔；如果没有这个抽象，{@link ConnectionPool} 只能硬编码 TCP 实现，测试替身和其他传输层都难以接入。
 * <p>
 * 上游由 {@link com.netcache.client.ClientBuilder} 和 {@link ConnectionPool} 调用；下游通常返回 {@link TcpNodeChannel} 这样的具体通道实现。
 * <p>
 * 线程安全说明：接口本身不持有状态，线程安全取决于实现；推荐实现为无状态或只读配置对象。
 * <p>
 * 典型用例：
 * <pre>{@code
 * NodeChannelFactory factory = NodeChannelFactory.tcp(connectTimeout, readTimeout);
 * NodeChannel channel = factory.connect("127.0.0.1:7001", 0);
 * channel.close();
 * }</pre>
 */
@FunctionalInterface
public interface NodeChannelFactory {
    /**
     * 为指定节点创建一条连接通道。
     *
     * @param seed 表示目标节点地址，通常为 {@code host:port}
     * @param poolIndex 表示该连接在节点连接池中的序号
     * @return 返回建立完成的节点通道
     * @throws RuntimeException 当连接建立失败或实现拒绝创建通道时抛出运行时异常
     * @implNote 连接池通常会在初始化阶段调用此方法多次，为同一节点预热多条长连接。
     */
    NodeChannel connect(String seed, int poolIndex);

    /**
     * 创建默认的 TCP 节点通道工厂。
     *
     * @param connectTimeout 表示建立连接的超时时间
     * @param readTimeout 表示等待节点响应的超时时间
     * @return 返回一个基于 {@link TcpNodeChannel} 的工厂实现
     * @implNote 该方法返回的是无状态 lambda，内部会把超时配置闭包捕获下来供每次建连复用。
     */
    static NodeChannelFactory tcp(Duration connectTimeout, Duration readTimeout) {
        return (seed, poolIndex) -> TcpNodeChannel.connect(seed, connectTimeout, readTimeout, poolIndex);
    }
}
