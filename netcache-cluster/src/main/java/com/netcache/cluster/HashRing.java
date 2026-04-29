package com.netcache.cluster;

import com.netcache.common.NodeId;
import com.netcache.common.util.HashUtil;
import com.netcache.cluster.migration.KeyMigration;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.TreeMap;

/**
 * 一致性哈希环 —— NetCache 的「分拣中心」。
 * <p>
 * 想象快递分拣：每个 key 先被映射成一个 64 位哈希值，再沿着圆环顺时针找到第一个虚拟节点，
 * 对应的物理节点就是这个 key 的归宿。这样在节点增减时，只会影响局部区段，而不会让全部 key
 * 重新洗牌。
 * <p>
 * 如果没有它，集群只能依赖简单取模路由；一旦节点数量变化，大量 key 都会迁移，缓存命中率和
 * 扩缩容成本都会明显恶化。
 * <p>
 * 协作关系：上游通常由 {@link ClusterTopology} 在拓扑变更与请求路由时调用；它依赖
 * {@link VirtualNode} 描述环上的虚拟槽位，依赖 {@link HashUtil} 计算 key 与虚拟节点哈希，
 * 并通过 {@link KeyMigration} 向外表达迁移区间。
 * <p>
 * 线程安全：线程安全。对环结构和节点映射的读写都通过实例级 {@code synchronized} 串行化，
 * 适合“写少读多但允许串行路由”的轻量集群控制面场景。
 * <p>
 * 典型用例：
 * <pre>{@code
 * HashRing ring = new HashRing();
 * ring.addNode(masterA);
 * ring.addNode(masterB);
 * NodeId owner = ring.routeOf("user:42".getBytes(StandardCharsets.UTF_8));
 * }</pre>
 * <p>
 * 环布局（简化示意图）：
 * <pre>
 *          0
 *          ↓
 *    [vn1] ──────→ [vn2]
 *     ↑              │
 *     │  顺时针方向   │
 *     │              ↓
 *     ←────── [vn3] ─┘
 * </pre>
 */
public final class HashRing {
    /** 每个物理节点默认切分出的虚拟节点数。 */
    public static final int DEFAULT_VIRTUAL_NODES = 160;

    /**
     * 环的主索引：key 为虚拟节点哈希，value 为对应的虚拟节点元数据。
     * <p>
     * 选择 {@link TreeMap} 是因为需要频繁执行 ceiling/lower/first/last 这类“顺时针找邻居”的导航操作。
     */
    private final NavigableMap<Long, VirtualNode> ring = new TreeMap<>();
    /** 物理节点到其全部虚拟节点的反向索引，便于节点下线时批量移除。 */
    private final Map<NodeId, List<VirtualNode>> nodeToVnodes = new HashMap<>();
    /** 每个物理节点在环上展开的虚拟节点数量。 */
    private final int virtualPerNode;

    /**
     * 创建使用默认虚拟节点数的哈希环。
     *
     * @implNote 默认值取 160，是为了在节点数较少时也能尽量摊平哈希热点。
     */
    public HashRing() {
        this(DEFAULT_VIRTUAL_NODES);
    }

    /**
     * 创建哈希环。
     *
     * @param virtualPerNode 每个物理节点对应的虚拟节点数量，必须大于 0
     * @throws IllegalArgumentException 当 {@code virtualPerNode <= 0} 时抛出
     * @implNote 虚拟节点越多，分布越均衡，但环维护成本也会同步增加。
     */
    public HashRing(int virtualPerNode) {
        if (virtualPerNode <= 0) {
            throw new IllegalArgumentException("virtualPerNode must be positive");
        }
        this.virtualPerNode = virtualPerNode;
    }

    /**
     * 计算给定 key 应路由到哪个物理节点。
     *
     * @param key 参与路由的原始 key 字节数组，不能为空
     * @return 顺时针命中的物理节点 ID
     * @throws NullPointerException 当 {@code key} 为 {@code null} 时抛出
     * @throws IllegalStateException 当哈希环中尚未加入任何节点时抛出
     * @implNote 先对 key 做 64 位哈希，再用 {@code ceilingEntry} 找到顺时针第一个虚拟节点；
     * 如果哈希值已经越过环尾，则回绕到 {@code firstEntry()}。
     */
    public synchronized NodeId routeOf(byte[] key) {
        Objects.requireNonNull(key, "key");
        if (ring.isEmpty()) {
            throw new IllegalStateException("hash ring has no nodes");
        }
        // 这里使用“顺时针第一个命中点”而不是最近距离，是为了保证一致性哈希的稳定迁移边界。
        long hash = HashUtil.hash64(key);
        Map.Entry<Long, VirtualNode> entry = ring.ceilingEntry(hash);
        if (entry == null) {
            entry = ring.firstEntry();
        }
        return entry.getValue().nodeId();
    }

    /**
     * 添加一个新的物理节点，并返回因此产生的 key 迁移区间。
     *
     * @param nodeId 新加入的物理节点 ID
     * @return 受该节点加入影响而需要迁移的区间列表；若节点已存在则返回空列表
     * @throws NullPointerException 当 {@code nodeId} 为 {@code null} 时抛出
     * @implNote 每个虚拟节点都会先计算“加入前该位置的所有者”，再插入自身，这样才能正确得出
     * 从旧 owner 到新节点的区间迁移关系。
     */
    public synchronized List<KeyMigration> addNode(NodeId nodeId) {
        Objects.requireNonNull(nodeId, "nodeId");
        if (nodeToVnodes.containsKey(nodeId)) {
            return List.of();
        }
        List<KeyMigration> migrations = new ArrayList<>();
        for (int i = 0; i < virtualPerNode; i++) {
            long hash = vnodeHash(nodeId, i);
            // 必须在 put 之前记录原 owner，否则会把“新节点自己”误判成迁移源头。
            NodeId source = ownerAt(hash);
            VirtualNode vnode = new VirtualNode(nodeId, i, hash);
            ring.put(hash, vnode);
            nodeToVnodes.computeIfAbsent(nodeId, ignored -> new ArrayList<>()).add(vnode);
            if (source != null && !source.equals(nodeId)) {
                long startExclusive = previousHash(hash);
                migrations.add(new KeyMigration(source, nodeId, startExclusive, hash));
            }
        }
        return List.copyOf(migrations);
    }

    /**
     * 移除一个物理节点，并返回其负责区间需要迁移到的新目标节点列表。
     *
     * @param nodeId 待移除的物理节点 ID
     * @return 迁移区间列表；若节点不存在则返回空列表
     * @throws NullPointerException 当 {@code nodeId} 为 {@code null} 时抛出
     * @implNote 先从反向索引中拿到所有虚拟节点，再逐个从环上移除，并重新计算该位置新的 owner。
     */
    public synchronized List<KeyMigration> removeNode(NodeId nodeId) {
        Objects.requireNonNull(nodeId, "nodeId");
        List<VirtualNode> removed = nodeToVnodes.remove(nodeId);
        if (removed == null || removed.isEmpty()) {
            return List.of();
        }
        List<KeyMigration> migrations = new ArrayList<>();
        for (VirtualNode vnode : removed) {
            ring.remove(vnode.hash());
            // 先移除再找 owner，才能得到“接手该区间的下一个节点”。
            NodeId target = ownerAt(vnode.hash());
            if (target != null) {
                migrations.add(new KeyMigration(nodeId, target, previousHash(vnode.hash()), vnode.hash()));
            }
        }
        return List.copyOf(migrations);
    }

    /**
     * 判断某个物理节点是否已经在环上注册。
     *
     * @param nodeId 待检查的节点 ID
     * @return {@code true} 表示该节点已经拥有虚拟节点映射，否则返回 {@code false}
     * @implNote 查询走反向索引，复杂度比遍历整个环更稳定。
     */
    public synchronized boolean contains(NodeId nodeId) {
        return nodeToVnodes.containsKey(nodeId);
    }

    /**
     * 返回当前环中全部物理节点。
     *
     * @return 物理节点 ID 列表快照
     * @implNote 返回不可变副本，避免调用方越权修改内部索引。
     */
    public synchronized List<NodeId> nodes() {
        return List.copyOf(nodeToVnodes.keySet());
    }

    /**
     * 返回当前环上的虚拟节点总数。
     *
     * @return 虚拟节点数量
     * @implNote 该值等于所有物理节点虚拟槽位之和，可用于观测分片密度。
     */
    public synchronized int virtualNodeCount() {
        return ring.size();
    }

    /**
     * 查找指定哈希点当前由哪个物理节点负责。
     *
     * @param hash 环上的目标哈希点
     * @return 该位置的物理节点 ID；若环为空则返回 {@code null}
     * @implNote 这是内部辅助方法，因此用 {@code null} 表示“环为空”这一状态，而不是抛异常。
     */
    private NodeId ownerAt(long hash) {
        if (ring.isEmpty()) {
            return null;
        }
        Map.Entry<Long, VirtualNode> entry = ring.ceilingEntry(hash);
        if (entry == null) {
            entry = ring.firstEntry();
        }
        return entry.getValue().nodeId();
    }

    /**
     * 查找指定哈希点在环上的前驱哈希值。
     *
     * @param hash 环上的目标哈希点
     * @return 前驱虚拟节点的哈希；若没有更小值则回绕返回环尾哈希
     * @implNote 迁移区间使用“前驱开区间 + 当前点闭区间”的表达方式，能明确边界归属。
     */
    private long previousHash(long hash) {
        if (ring.isEmpty()) {
            return hash;
        }
        Long previous = ring.lowerKey(hash);
        return previous == null ? ring.lastKey() : previous;
    }

    /**
     * 为虚拟节点生成稳定哈希值。
     *
     * @param nodeId 物理节点 ID
     * @param index 该物理节点下的虚拟节点序号
     * @return 对应虚拟节点在环上的 64 位位置
     * @implNote 这里显式拼接 UUID 的高低位与序号，而不是直接拼字符串，是为了减少编码差异并保持哈希输入稳定。
     */
    private static long vnodeHash(NodeId nodeId, int index) {
        // 字节布局：| UUID MSB | UUID LSB | vnode index |
        // 这样既能稳定区分物理节点，也能让同一节点的不同虚拟槽位均匀散开。
        ByteBuffer buffer = ByteBuffer.allocate(Long.BYTES * 2 + Integer.BYTES);
        buffer.putLong(nodeId.id().getMostSignificantBits());
        buffer.putLong(nodeId.id().getLeastSignificantBits());
        buffer.putInt(index);
        return HashUtil.hash64(buffer.array());
    }
}
