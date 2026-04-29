package com.netcache.cluster;

import com.netcache.common.NodeId;
import com.netcache.common.NodeRole;

import java.util.Collection;
import java.util.Comparator;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 集群拓扑 —— NetCache 的「地图册」。
 * <p>
 * 它负责保存当前 epoch 下有哪些节点、每个节点扮演什么角色，以及 master 节点如何映射到
 * 一致性哈希环。没有它，客户端路由和 Sentinel 切换就缺少统一的事实来源，容易出现“请求打到旧主”
 * 或“角色视图不一致”的问题。
 * <p>
 * 协作关系：上游由客户端路由层、Sentinel 故障切换流程和集群控制面共同调用；内部依赖
 * {@link HashRing} 处理 master 路由，依赖 {@link NodeEndpoint} 描述节点元信息。
 * <p>
 * 线程安全：部分线程安全。节点表使用 {@link ConcurrentHashMap}，epoch 使用 {@link AtomicLong}；
 * 但 {@link #apply(long, Collection)} 是多步替换流程，只保证方法内部顺序执行，不提供跨对象的事务隔离。
 * <p>
 * 典型用例：
 * <pre>{@code
 * ClusterTopology topology = new ClusterTopology();
 * topology.apply(1L, nodes);
 * NodeEndpoint owner = topology.route("user:42".getBytes(StandardCharsets.UTF_8));
 * long epoch = topology.epoch();
 * }</pre>
 */
public final class ClusterTopology {
    /** 当前拓扑版本号，越大表示视图越新。 */
    private final AtomicLong epoch = new AtomicLong();
    /** 节点 ID 到网络端点的最新快照。 */
    private final ConcurrentHashMap<NodeId, NodeEndpoint> nodes = new ConcurrentHashMap<>();
    /** 仅包含 master 节点的一致性哈希环。 */
    private final HashRing hashRing;

    /**
     * 创建使用默认哈希环的拓扑对象。
     *
     * @implNote 默认构造适合普通运行场景，测试时可注入自定义环实现或参数。
     */
    public ClusterTopology() {
        this(new HashRing());
    }

    /**
     * 创建拓扑对象。
     *
     * @param hashRing 用于 master 路由的一致性哈希环
     * @throws NullPointerException 当 {@code hashRing} 为 {@code null} 时抛出
     * @implNote 将路由结构作为依赖注入，便于测试中构造不同分布策略。
     */
    public ClusterTopology(HashRing hashRing) {
        this.hashRing = Objects.requireNonNull(hashRing, "hashRing");
    }

    /**
     * 应用一份新的拓扑快照。
     *
     * @param newEpoch 新拓扑的版本号，必须严格大于当前 epoch 才会生效
     * @param newNodes 新拓扑中的全部节点
     * @return {@code true} 表示成功替换当前拓扑；{@code false} 表示因 epoch 过旧而被忽略
     * @throws NullPointerException 当 {@code newNodes} 为 {@code null} 时抛出
     * @implNote 先构建替代用的哈希环，再整体替换现有 ring 内容，避免在半更新状态下对外暴露不完整路由。
     */
    public boolean apply(long newEpoch, Collection<NodeEndpoint> newNodes) {
        Objects.requireNonNull(newNodes, "newNodes");
        if (newEpoch <= epoch.get()) {
            return false;
        }
        nodes.clear();
        HashRing replacement = new HashRing();
        for (NodeEndpoint node : newNodes) {
            nodes.put(node.nodeId(), node);
            if (node.role() == NodeRole.MASTER) {
                replacement.addNode(node.nodeId());
            }
        }
        synchronized (hashRing) {
            // 不直接替换 hashRing 引用，而是重建内容，是为了让持有该实例引用的其他协作者继续看到同一个对象。
            for (NodeId nodeId : hashRing.nodes()) {
                hashRing.removeNode(nodeId);
            }
            for (NodeId nodeId : replacement.nodes()) {
                hashRing.addNode(nodeId);
            }
        }
        epoch.set(newEpoch);
        return true;
    }

    /**
     * 返回当前拓扑版本号。
     *
     * @return 当前 epoch
     * @implNote 通过 {@link AtomicLong} 读取，避免无锁场景下看到撕裂值。
     */
    public long epoch() {
        return epoch.get();
    }

    /**
     * 查询单个节点的端点信息。
     *
     * @param nodeId 目标节点 ID
     * @return 对应节点的端点信息；若不存在则返回空
     * @implNote 使用 {@link Optional} 暴露“可能不存在”，避免返回 {@code null} 给上游。
     */
    public Optional<NodeEndpoint> node(NodeId nodeId) {
        return Optional.ofNullable(nodes.get(nodeId));
    }

    /**
     * 根据 key 路由到负责它的 master 节点。
     *
     * @param key 原始 key 字节数组
     * @return 负责该 key 的节点端点
     * @throws IllegalStateException 当路由到的节点不在当前拓扑中时抛出
     * @implNote 先通过哈希环得到 master 节点 ID，再回表拿到完整端点信息；两者不一致通常意味着拓扑更新流程异常。
     */
    public NodeEndpoint route(byte[] key) {
        NodeId nodeId = hashRing.routeOf(key);
        NodeEndpoint endpoint = nodes.get(nodeId);
        if (endpoint == null) {
            throw new IllegalStateException("routed node is missing from topology: " + nodeId);
        }
        return endpoint;
    }

    /**
     * 返回当前全部节点的有序快照。
     *
     * @return 按地址排序后的节点集合
     * @implNote 排序是为了让日志、测试断言和拓扑广播结果保持稳定，不受哈希表遍历顺序影响。
     */
    public Collection<NodeEndpoint> nodes() {
        return nodes.values().stream()
                .sorted(Comparator.comparing(NodeEndpoint::address))
                .toList();
    }

    /**
     * 返回节点映射的不可变快照。
     *
     * @return 当前节点表副本
     * @implNote 通过只读副本隔离内部状态，避免外部调用方修改共享表。
     */
    public Map<NodeId, NodeEndpoint> nodeMap() {
        return Map.copyOf(nodes);
    }
}
