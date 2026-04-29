package com.netcache.client.pool;

import com.netcache.client.routing.TopologyCache;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 节点连接池，像给每个缓存节点配的一组“车道”，负责持有多条长连接并按轮询方式分摊请求流量。
 * <p>
 * 之所以需要它，是因为单连接既容易成为瓶颈，也不利于并发隔离；如果没有连接池，所有请求只能挤在同一条通道上，延迟和抖动会更明显。
 * <p>
 * 上游由 {@link com.netcache.client.routing.RequestRouter} 按节点地址取连接；下游依赖 {@link NodeChannelFactory} 创建真实连接，
 * 并依赖 {@link TopologyCache} 提供种子节点集合。
 * <p>
 * 线程安全说明：该类支持并发使用。内部用 {@link ConcurrentHashMap} 保存节点连接列表，并用每节点的 {@link AtomicInteger}
 * 实现无锁轮询游标。
 * <p>
 * 典型用例：
 * <pre>{@code
 * TopologyCache topology = new TopologyCache(List.of("127.0.0.1:7001"));
 * ConnectionPool pool = new ConnectionPool(topology, 8, NodeChannelFactory.tcp(connectTimeout, readTimeout));
 * NodeChannel channel = pool.channel("127.0.0.1:7001");
 * }</pre>
 */
public final class ConnectionPool implements AutoCloseable {
    /** 按节点地址分组保存该节点持有的所有长连接。 */
    private final ConcurrentHashMap<String, List<NodeChannel>> channelsBySeed = new ConcurrentHashMap<>();
    /** 为每个节点维护一个独立的轮询游标，避免热点请求总是落到同一条连接。 */
    private final ConcurrentHashMap<String, AtomicInteger> cursors = new ConcurrentHashMap<>();

    /**
     * 为所有种子节点预创建连接池。
     *
     * @param topologyCache 表示节点拓扑与种子列表来源，不能为空
     * @param poolSizePerNode 表示每个节点要建立的连接数，必须大于 0
     * @param channelFactory 表示用于创建单条节点连接的工厂，不能为空
     * @throws NullPointerException 当 {@code topologyCache} 或 {@code channelFactory} 为 {@code null} 时抛出
     * @throws IllegalArgumentException 当 {@code poolSizePerNode} 小于等于 0 时抛出
     * @implNote 当前实现会立即为每个种子节点同步建立所有连接，而不是按需懒加载。
     */
    public ConnectionPool(TopologyCache topologyCache, int poolSizePerNode, NodeChannelFactory channelFactory) {
        Objects.requireNonNull(topologyCache, "topologyCache");
        Objects.requireNonNull(channelFactory, "channelFactory");
        if (poolSizePerNode <= 0) {
            throw new IllegalArgumentException("poolSizePerNode must be positive");
        }
        for (String seed : topologyCache.seeds()) {
            List<NodeChannel> channels = new ArrayList<>(poolSizePerNode);
            for (int i = 0; i < poolSizePerNode; i++) {
                channels.add(channelFactory.connect(seed, i));
            }
            channelsBySeed.put(seed, List.copyOf(channels));
            cursors.put(seed, new AtomicInteger());
        }
    }

    /**
     * 获取某个节点下一条可用连接。
     *
     * @param seed 表示目标节点地址，通常为 {@code host:port}
     * @return 返回根据轮询策略选中的节点连接
     * @throws IllegalStateException 当目标节点没有可用连接时抛出
     * @implNote 选择逻辑基于每节点独立游标做 {@code floorMod}，即使计数器溢出也能安全回绕。
     */
    public NodeChannel channel(String seed) {
        List<NodeChannel> channels = channelsBySeed.get(seed);
        if (channels == null || channels.isEmpty()) {
            throw new IllegalStateException("no channels for seed " + seed);
        }
        int index = Math.floorMod(cursors.get(seed).getAndIncrement(), channels.size());
        return channels.get(index);
    }

    @Override
    /**
     * 关闭连接池中的全部节点连接。
     *
     * @implNote 当前实现采用 best-effort 级联关闭，不聚合单条连接的关闭异常。
     */
    public void close() {
        channelsBySeed.values().forEach(channels -> channels.forEach(NodeChannel::close));
    }
}
