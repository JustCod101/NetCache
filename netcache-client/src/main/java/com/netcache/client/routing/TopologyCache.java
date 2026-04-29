package com.netcache.client.routing;

import com.netcache.cluster.ClusterTopology;
import com.netcache.cluster.NodeEndpoint;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 客户端本地拓扑缓存，像一张随手可查的“路线图”，负责保存种子节点与最新哈希环视图，并回答 key 应该去哪台节点。
 * <p>
 * 之所以需要它，是因为客户端必须在本地快速做路由决策；如果每次请求都去远端查询拓扑，不但延迟更高，也会把控制面耦合进数据面。
 * <p>
 * 上游由 {@link com.netcache.client.ClientBuilder} 初始化，也可能由 MOVED 处理逻辑刷新；下游被 {@link RequestRouter} 和其他路由相关组件查询。
 * <p>
 * 线程安全说明：该类支持并发使用。种子列表不可变，轮询游标使用 {@link AtomicInteger}，最新拓扑快照使用 {@link AtomicReference} 原子替换。
 * <p>
 * 典型用例：
 * <pre>{@code
 * TopologyCache cache = new TopologyCache(List.of("127.0.0.1:7001"));
 * String seed = cache.route("key".getBytes());
 * cache.updateTopology(clusterTopology);
 * }</pre>
 */
public final class TopologyCache {
    private final List<String> seeds;
    /** 当没有 key 或需要轮询选种子节点时使用的游标。 */
    private final AtomicInteger cursor = new AtomicInteger();
    /** 保存最新的集群拓扑快照；为空时表示仍在使用种子节点级别的退化路由。 */
    private final AtomicReference<ClusterTopology> topology = new AtomicReference<>();

    /**
     * 创建一个只带种子节点的拓扑缓存。
     *
     * @param seeds 表示用于初始连通和退化路由的种子节点列表，不能为空且不能为空列表
     * @throws NullPointerException 当 {@code seeds} 为 {@code null} 时抛出
     * @throws IllegalArgumentException 当种子列表为空时抛出
     * @implNote 构造后种子列表会被复制成不可变快照，防止外部继续修改。
     */
    public TopologyCache(List<String> seeds) {
        Objects.requireNonNull(seeds, "seeds");
        if (seeds.isEmpty()) {
            throw new IllegalArgumentException("seeds must not be empty");
        }
        this.seeds = List.copyOf(seeds);
    }

    /**
     * 返回构造时配置的种子节点列表。
     *
     * @return 返回不可变的种子节点列表
     * @implNote 返回的是内部不可变快照，可安全在外部遍历但不允许修改。
     */
    public List<String> seeds() {
        return seeds;
    }

    /**
     * 根据键路由到目标节点地址。
     *
     * @param key 表示用于哈希路由的键字节数组
     * @return 返回目标节点地址字符串
     * @implNote 优先使用最新拓扑的哈希环；若拓扑尚未刷新，则退化为单节点直连或基于 {@code Arrays.hashCode(key)} 的种子列表散列。
     */
    public String route(byte[] key) {
        ClusterTopology current = topology.get();
        if (current != null) {
            return current.route(key).address();
        }
        if (seeds.size() == 1) {
            return seeds.get(0);
        }
        return seeds.get(Math.floorMod(java.util.Arrays.hashCode(key), seeds.size()));
    }

    /**
     * 用最新集群拓扑替换本地缓存。
     *
     * @param clusterTopology 表示新的集群拓扑快照，不能为空
     * @throws NullPointerException 当 {@code clusterTopology} 为 {@code null} 时抛出
     * @implNote 更新采用原子替换，新老读线程之间不会看到部分更新状态。
     */
    public void updateTopology(ClusterTopology clusterTopology) {
        topology.set(Objects.requireNonNull(clusterTopology, "clusterTopology"));
    }

    /**
     * 根据键尝试返回更完整的节点端点信息。
     *
     * @param key 表示用于路由的键字节数组
     * @return 返回包含节点端点的 {@link Optional}；若本地尚无拓扑快照则返回空
     * @implNote 该方法适合需要拿到节点元信息而不仅仅是地址字符串的调用方。
     */
    public Optional<NodeEndpoint> routeEndpoint(byte[] key) {
        ClusterTopology current = topology.get();
        return current == null ? Optional.empty() : Optional.of(current.route(key));
    }

    /**
     * 轮询选择下一个种子节点。
     *
     * @return 返回下一个种子节点地址
     * @implNote 常用于无 key 请求或拓扑尚未可用时的退化投递路径。
     */
    public String nextSeed() {
        return seeds.get(Math.floorMod(cursor.getAndIncrement(), seeds.size()));
    }
}
