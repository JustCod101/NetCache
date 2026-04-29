package com.netcache.client;

import com.netcache.client.pool.ConnectionPool;
import com.netcache.client.pool.NodeChannelFactory;
import com.netcache.client.retry.RetryPolicy;
import com.netcache.client.routing.RequestRouter;
import com.netcache.client.routing.TopologyCache;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * NetCache 客户端构建器，像装配台一样把种子节点、连接池、超时和重试参数拼装成一个可用客户端。
 * <p>
 * 之所以需要它，是为了把繁琐的依赖装配和默认值管理从业务代码里抽走；如果没有构建器，调用方就得理解连接池、拓扑缓存和重试策略的组合方式。
 * <p>
 * 上游通常由 {@link NetCacheClient#builder()} 生成后交给应用启动代码配置；下游会创建 {@link TopologyCache}、{@link ConnectionPool}、
 * {@link RequestRouter} 与 {@link RetryPolicy}。
 * <p>
 * 线程安全说明：该类不是线程安全的。它是典型的可变流式配置对象，应在单线程初始化阶段使用，构建完成后即可丢弃。
 * <p>
 * 典型用例：
 * <pre>{@code
 * NetCacheClient client = NetCacheClient.builder()
 *         .seeds("127.0.0.1:7001", "127.0.0.1:7002")
 *         .poolSizePerNode(8)
 *         .maxRetries(3)
 *         .build();
 * }</pre>
 */
public final class ClientBuilder {
    private final List<String> seeds = new ArrayList<>();
    private int poolSizePerNode = 8;
    private Duration connectTimeout = Duration.ofMillis(500);
    private Duration readTimeout = Duration.ofSeconds(2);
    private int maxRetries = 3;
    private NodeChannelFactory channelFactory;

    /**
     * 设置用于发现集群的种子节点列表。
     *
     * @param seeds 表示一个或多个 {@code host:port} 字符串，不能为空
     * @return 返回当前构建器，便于链式调用
     * @throws NullPointerException 当 {@code seeds} 数组本身为 {@code null} 时抛出
     * @implNote 该方法会先清空旧配置，再整体替换为新传入的种子列表。
     */
    public ClientBuilder seeds(String... seeds) {
        this.seeds.clear();
        this.seeds.addAll(Arrays.asList(Objects.requireNonNull(seeds, "seeds")));
        return this;
    }

    /**
     * 设置每个节点持有的长连接数量。
     *
     * @param poolSizePerNode 表示每个节点的连接数，必须大于 0
     * @return 返回当前构建器，便于链式调用
     * @throws IllegalArgumentException 当连接数小于等于 0 时抛出
     * @implNote 连接池会为每个种子节点预先建立这么多连接，并采用轮询分发请求。
     */
    public ClientBuilder poolSizePerNode(int poolSizePerNode) {
        if (poolSizePerNode <= 0) {
            throw new IllegalArgumentException("poolSizePerNode must be positive");
        }
        this.poolSizePerNode = poolSizePerNode;
        return this;
    }

    /**
     * 设置建立 TCP 连接时的超时。
     *
     * @param connectTimeout 表示连接建立的最长等待时间，不能为空
     * @return 返回当前构建器，便于链式调用
     * @throws NullPointerException 当 {@code connectTimeout} 为 {@code null} 时抛出
     * @implNote 该超时会透传给默认的 TCP 通道工厂，用于约束初次握手阶段。
     */
    public ClientBuilder connectTimeout(Duration connectTimeout) {
        this.connectTimeout = Objects.requireNonNull(connectTimeout, "connectTimeout");
        return this;
    }

    /**
     * 设置单次读响应的超时。
     *
     * @param readTimeout 表示等待服务端响应的最长时间，不能为空
     * @return 返回当前构建器，便于链式调用
     * @throws NullPointerException 当 {@code readTimeout} 为 {@code null} 时抛出
     * @implNote 该值最终会交给 {@link com.netcache.client.routing.ResponseRouter}，用于清理超时未完成的请求。
     */
    public ClientBuilder readTimeout(Duration readTimeout) {
        this.readTimeout = Objects.requireNonNull(readTimeout, "readTimeout");
        return this;
    }

    /**
     * 设置最多允许的重试次数。
     *
     * @param maxRetries 表示失败后的最大重试次数，必须大于等于 0
     * @return 返回当前构建器，便于链式调用
     * @throws IllegalArgumentException 当重试次数为负数时抛出
     * @implNote 这里的值会原样注入 {@link RetryPolicy}，不包含首次尝试本身。
     */
    public ClientBuilder maxRetries(int maxRetries) {
        if (maxRetries < 0) {
            throw new IllegalArgumentException("maxRetries must be non-negative");
        }
        this.maxRetries = maxRetries;
        return this;
    }

    /**
     * 设置自定义节点通道工厂。
     *
     * @param channelFactory 表示负责创建节点连接的工厂实现，不能为空
     * @return 返回当前构建器，便于链式调用
     * @throws NullPointerException 当 {@code channelFactory} 为 {@code null} 时抛出
     * @implNote 若显式提供工厂，则构建阶段不会再创建默认 TCP 工厂，便于测试替身或其他传输实现接入。
     */
    public ClientBuilder channelFactory(NodeChannelFactory channelFactory) {
        this.channelFactory = Objects.requireNonNull(channelFactory, "channelFactory");
        return this;
    }

    /**
     * 构建一个可用的 NetCache 客户端实例。
     *
     * @return 返回装配完成的 {@link NetCacheClient}
     * @throws IllegalStateException 当未配置任何种子节点时抛出
     * @implNote 构建顺序为：选择通道工厂 → 创建拓扑缓存 → 创建连接池 → 创建请求路由器 → 包装成默认客户端。
     */
    public NetCacheClient build() {
        if (seeds.isEmpty()) {
            throw new IllegalStateException("at least one seed is required");
        }
        NodeChannelFactory factory = channelFactory == null
                ? NodeChannelFactory.tcp(connectTimeout, readTimeout)
                : channelFactory;
        TopologyCache topologyCache = new TopologyCache(seeds);
        ConnectionPool pool = new ConnectionPool(topologyCache, poolSizePerNode, factory);
        return new DefaultNetCacheClient(new RequestRouter(topologyCache, pool), new RetryPolicy(maxRetries));
    }
}
