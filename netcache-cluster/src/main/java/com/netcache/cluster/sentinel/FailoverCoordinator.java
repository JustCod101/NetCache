package com.netcache.cluster.sentinel;

import com.netcache.cluster.ClusterTopology;
import com.netcache.cluster.NodeEndpoint;
import com.netcache.common.NodeId;
import com.netcache.common.NodeRole;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 故障切换协调器 —— Sentinel 的「总指挥」。
 * <p>
 * 它把 ODOWN 判定、leader 选举、最佳 slave 挑选和新拓扑生成串成一条完整 failover 决策链。没有它，
 * 每个 Sentinel 都可能各自为战，导致重复切换或选出不同的新主。
 * <p>
 * 协作关系：上游由 {@link SentinelNode} 在满足条件时调用；内部依赖 {@link RaftLite} 产出本轮 leader，
 * 依赖 {@link ClusterTopology} 与 {@link NodeEndpoint} 生成新的角色布局。
 * <p>
 * 线程安全：弱线程安全。配置字段不可变，但 failover 本身是纯计算协调逻辑，没有内部锁，通常应由上层按节点事件顺序调用。
 * <p>
 * 典型用例：
 * <pre>{@code
 * Optional<FailoverResult> result = coordinator.failover(
 *         topology, failedMasterId, votes, sentinels, offsets, priorities, startedAt, nowMs);
 * result.ifPresent(r -> topology.apply(r.epoch(), r.nodes()));
 * }</pre>
 */
public final class FailoverCoordinator {
    /** 轻量 leader 选举器。 */
    private final RaftLite raftLite;
    /** 触发 ODOWN / failover 所需法定票数。 */
    private final int quorum;
    /** 单轮 failover 最长允许持续时间。 */
    private final long failoverTimeoutMs;

    /**
     * 创建故障切换协调器。
     *
     * @param raftLite leader 选举器
     * @param quorum 法定票数
     * @param failoverTimeoutMs failover 超时时间
     * @throws NullPointerException 当 {@code raftLite} 为 {@code null} 时抛出
     * @throws IllegalArgumentException 当 {@code quorum <= 0} 或 {@code failoverTimeoutMs <= 0} 时抛出
     * @implNote 选举器和超时窗口都是策略参数，拆出来便于后续替换更真实的实现。
     */
    public FailoverCoordinator(RaftLite raftLite, int quorum, long failoverTimeoutMs) {
        this.raftLite = Objects.requireNonNull(raftLite, "raftLite");
        if (quorum <= 0) {
            throw new IllegalArgumentException("quorum must be > 0");
        }
        if (failoverTimeoutMs <= 0) {
            throw new IllegalArgumentException("failoverTimeoutMs must be > 0");
        }
        this.quorum = quorum;
        this.failoverTimeoutMs = failoverTimeoutMs;
    }

    /**
     * 对一个故障 master 执行完整的 failover 计算。
     *
     * @param topology 当前集群拓扑
     * @param failedMasterId 失效 master ID
     * @param objectiveDownVotes 已经认定其下线的 Sentinel 投票集合
     * @param participatingSentinels 本轮参与选举的 Sentinel 集合
     * @param replicationOffsets 各 slave 当前复制 offset
     * @param priorities 各 slave 优先级
     * @param failoverStartedAtMs 本轮 failover 启动时间
     * @param nowMs 当前时间戳
     * @return 切换成功时返回包含新拓扑的结果，否则返回空
     * @throws NullPointerException 当关键输入参数为 {@code null} 时抛出
     * @implNote 执行顺序是：先过 quorum/超时门槛，再选举 leader，最后按 offset/priority/address 选最优 slave 并重写拓扑。
     */
    public Optional<FailoverResult> failover(ClusterTopology topology,
                                             NodeId failedMasterId,
                                             Set<NodeId> objectiveDownVotes,
                                             Collection<NodeId> participatingSentinels,
                                             Map<NodeId, Long> replicationOffsets,
                                             Map<NodeId, Integer> priorities,
                                             long failoverStartedAtMs,
                                             long nowMs) {
        Objects.requireNonNull(topology, "topology");
        Objects.requireNonNull(failedMasterId, "failedMasterId");
        Objects.requireNonNull(objectiveDownVotes, "objectiveDownVotes");
        Objects.requireNonNull(participatingSentinels, "participatingSentinels");
        Objects.requireNonNull(replicationOffsets, "replicationOffsets");
        Objects.requireNonNull(priorities, "priorities");

        if (objectiveDownVotes.size() < quorum || nowMs - failoverStartedAtMs > failoverTimeoutMs) {
            return Optional.empty();
        }

        Optional<RaftLite.ElectionResult> election = raftLite.electLeader(participatingSentinels, quorum);
        if (election.isEmpty()) {
            return Optional.empty();
        }

        // 选 slave 的优先级：复制越新越好；复制位点相同时，priority 越高越优；最后用地址稳定打破平局。
        Optional<NodeEndpoint> candidate = topology.nodes().stream()
                .filter(node -> node.role() == NodeRole.SLAVE && failedMasterId.equals(node.masterId()))
                .max(Comparator
                        .comparingLong((NodeEndpoint node) -> replicationOffsets.getOrDefault(node.nodeId(), 0L))
                        .thenComparingInt(node -> priorities.getOrDefault(node.nodeId(), 0))
                        .thenComparing(NodeEndpoint::address));
        if (candidate.isEmpty()) {
            return Optional.empty();
        }

        NodeEndpoint promoted = candidate.get();
        List<NodeEndpoint> updatedNodes = topology.nodes().stream()
                .map(node -> rewriteNode(node, failedMasterId, promoted.nodeId()))
                .toList();

        long newEpoch = topology.epoch() + 1;
        return Optional.of(new FailoverResult(
                election.get().term(),
                election.get().leaderId(),
                failedMasterId,
                promoted.nodeId(),
                newEpoch,
                updatedNodes));
    }

    /**
     * 按 failover 结果重写单个节点的角色与主从归属。
     *
     * @param node 原始节点
     * @param failedMasterId 故障 master ID
     * @param promotedNodeId 被提升的新主 ID
     * @return 重写后的节点端点
     * @implNote 故障 master 本身以及原来挂在它下面的 slaves 都会重新指向新主，避免形成悬空复制链。
     */
    private static NodeEndpoint rewriteNode(NodeEndpoint node, NodeId failedMasterId, NodeId promotedNodeId) {
        if (node.nodeId().equals(promotedNodeId)) {
            return new NodeEndpoint(node.nodeId(), node.host(), node.port(), NodeRole.MASTER, null);
        }
        if (node.nodeId().equals(failedMasterId)
                || (node.role() == NodeRole.SLAVE && failedMasterId.equals(node.masterId()))) {
            return new NodeEndpoint(node.nodeId(), node.host(), node.port(), NodeRole.SLAVE, promotedNodeId);
        }
        return node;
    }

    /**
     * failover 完成后的结果快照。
     * <p>
     * 这相当于一次“切换方案书”：里面既有本轮选举信息，也有新 epoch 和重写后的全部节点列表。
     *
     * @param term 本轮选举任期
     * @param leaderId 负责本轮切换的 Sentinel leader
     * @param failedMasterId 故障 master ID
     * @param promotedMasterId 被提升的新 master ID
     * @param epoch 新拓扑 epoch
     * @param nodes 重写后的完整节点列表
     */
    public record FailoverResult(long term,
                                 NodeId leaderId,
                                 NodeId failedMasterId,
                                 NodeId promotedMasterId,
                                 long epoch,
                                 List<NodeEndpoint> nodes) {
        /**
         * 校验 failover 结果的必要字段。
         *
         * @throws NullPointerException 当关键字段为 {@code null} 时抛出
         * @implNote 这里不校验 term/epoch 的范围，因为它们由上层协调逻辑控制增长。
         */
        public FailoverResult {
            Objects.requireNonNull(leaderId, "leaderId");
            Objects.requireNonNull(failedMasterId, "failedMasterId");
            Objects.requireNonNull(promotedMasterId, "promotedMasterId");
            Objects.requireNonNull(nodes, "nodes");
        }
    }
}
