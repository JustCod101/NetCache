package com.netcache.cluster.sentinel;

import com.netcache.cluster.ClusterTopology;
import com.netcache.common.NodeId;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Sentinel 节点 —— 故障探测与切换流程的「值班哨兵」。
 * <p>
 * 它把健康检查、SDOWN/ODOWN 判定和 failover 协调串起来，代表单个 Sentinel 实例对外暴露统一入口。
 * 没有它，这些能力会散落在多个协作者里，调用顺序和状态流转都难以维护。
 * <p>
 * 协作关系：上游通常由监控循环或测试编排驱动；内部依赖 {@link HealthChecker} 判定主观下线，
 * 依赖 {@link QuorumDecision} 汇总投票，依赖 {@link FailoverCoordinator} 执行选主和拓扑重写，
 * 并最终更新 {@link ClusterTopology}。
 * <p>
 * 线程安全：部分线程安全。内部协作者大多具备并发容器或原子语义，但本类没有额外的整体事务锁，
 * 因此更适合由单个事件循环顺序驱动。
 * <p>
 * 典型用例：
 * <pre>{@code
 * sentinel.recordPing(masterId, false, nowMs);
 * sentinel.observePeerSdown(masterId, peerSentinelId);
 * Optional<FailoverResult> result = sentinel.tryFailover(masterId, offsets, priorities, nowMs);
 * }</pre>
 */
public final class SentinelNode {
    /** 当前 Sentinel 自身的唯一标识。 */
    private final NodeId sentinelId;
    /** 同一监控组里参与协商的其他 Sentinel。 */
    private final Set<NodeId> peerSentinels;
    /** 判定 ODOWN 和发起 failover 所需的法定票数。 */
    private final int quorum;
    /** Sentinel 当前持有并负责更新的集群拓扑。 */
    private final ClusterTopology topology;
    /** 主观下线探测器。 */
    private final HealthChecker healthChecker;
    /** 客观下线投票聚合器。 */
    private final QuorumDecision quorumDecision;
    /** 故障切换协调器。 */
    private final FailoverCoordinator failoverCoordinator;

    /**
     * 创建带默认协作者装配的 Sentinel 节点。
     *
     * @param sentinelId 当前 Sentinel ID
     * @param peerSentinels 其他 Sentinel 集合
     * @param quorum 法定票数
     * @param pingIntervalMs 探活间隔
     * @param sdownAfterMs 进入主观下线阈值
     * @param failoverTimeoutMs failover 最大持续时间
     * @param topology 当前集群拓扑
     * @implNote 该构造用于常规装配；若测试要精确控制选举和投票逻辑，可使用注入式构造函数。
     */
    public SentinelNode(NodeId sentinelId,
                        Collection<NodeId> peerSentinels,
                        int quorum,
                        long pingIntervalMs,
                        long sdownAfterMs,
                        long failoverTimeoutMs,
                        ClusterTopology topology) {
        this(sentinelId,
                peerSentinels,
                quorum,
                topology,
                new HealthChecker(pingIntervalMs, sdownAfterMs),
                new QuorumDecision(),
                new FailoverCoordinator(new RaftLite(), quorum, failoverTimeoutMs));
    }

    /**
     * 创建自定义协作者的 Sentinel 节点。
     *
     * @param sentinelId 当前 Sentinel ID
     * @param peerSentinels 其他 Sentinel 集合
     * @param quorum 法定票数
     * @param topology 当前拓扑
     * @param healthChecker 健康检查器
     * @param quorumDecision 投票聚合器
     * @param failoverCoordinator 故障切换协调器
     * @throws NullPointerException 当关键依赖为 {@code null} 时抛出
     * @throws IllegalArgumentException 当 {@code quorum <= 0} 时抛出
     * @implNote 依赖注入让故障场景测试可以分别替换心跳、投票与选举行为。
     */
    public SentinelNode(NodeId sentinelId,
                        Collection<NodeId> peerSentinels,
                        int quorum,
                        ClusterTopology topology,
                        HealthChecker healthChecker,
                        QuorumDecision quorumDecision,
                        FailoverCoordinator failoverCoordinator) {
        this.sentinelId = Objects.requireNonNull(sentinelId, "sentinelId");
        this.peerSentinels = Set.copyOf(Objects.requireNonNull(peerSentinels, "peerSentinels"));
        if (quorum <= 0) {
            throw new IllegalArgumentException("quorum must be > 0");
        }
        this.quorum = quorum;
        this.topology = Objects.requireNonNull(topology, "topology");
        this.healthChecker = Objects.requireNonNull(healthChecker, "healthChecker");
        this.quorumDecision = Objects.requireNonNull(quorumDecision, "quorumDecision");
        this.failoverCoordinator = Objects.requireNonNull(failoverCoordinator, "failoverCoordinator");
    }

    /**
     * 返回当前 Sentinel 的 ID。
     *
     * @return Sentinel ID
     * @implNote 该值也会作为当前节点投票、选主时的身份标识。
     */
    public NodeId sentinelId() {
        return sentinelId;
    }

    /**
     * 返回当前持有的拓扑对象。
     *
     * @return 集群拓扑
     * @implNote 暴露引用而不是拷贝，是为了让 failover 成果能直接应用在共享拓扑对象上。
     */
    public ClusterTopology topology() {
        return topology;
    }

    /**
     * 记录一次对某节点的探活结果。
     *
     * @param nodeId 被探测节点 ID
     * @param healthy 本次探测是否健康
     * @param nowMs 当前时间戳（毫秒）
     * @implNote 只有健康结果会刷新“最近成功时间”，失败不会写入时间戳，从而保留连续失败窗口。
     */
    public void recordPing(NodeId nodeId, boolean healthy, long nowMs) {
        healthChecker.recordPing(nodeId, healthy, nowMs);
    }

    /**
     * 判断某节点是否已进入主观下线状态。
     *
     * @param nodeId 目标节点 ID
     * @param nowMs 当前时间戳（毫秒）
     * @return {@code true} 表示本 Sentinel 认为其已下线
     * @implNote SDOWN 是单 Sentinel 局部视角，不能直接触发 failover，仍需结合投票进入 ODOWN。
     */
    public boolean isSubjectivelyDown(NodeId nodeId, long nowMs) {
        return healthChecker.isSubjectivelyDown(nodeId, nowMs);
    }

    /**
     * 记录来自其他 Sentinel 的 SDOWN 观察票。
     *
     * @param targetNodeId 被报告下线的目标节点
     * @param reportingSentinelId 报告该观察结果的 Sentinel ID
     * @implNote 这里只负责收票，不在此处直接判定是否满足 quorum，便于上层按需触发更复杂流程。
     */
    public void observePeerSdown(NodeId targetNodeId, NodeId reportingSentinelId) {
        quorumDecision.recordSdownVote(targetNodeId, reportingSentinelId);
    }

    /**
     * 尝试对指定故障 master 发起 failover。
     *
     * @param failedMasterId 被怀疑故障的 master ID
     * @param replicationOffsets 各 slave 当前复制 offset
     * @param priorities 各 slave 的优先级
     * @param nowMs 当前时间戳（毫秒）
     * @return failover 成功时返回结果，否则返回空
     * @throws NullPointerException 当 {@code failedMasterId} 为 {@code null} 时抛出
     * @implNote 先确认本地 SDOWN，再把自己的一票记入 quorum，最后交给协调器执行 ODOWN 检查、选举和拓扑改写。
     */
    public Optional<FailoverCoordinator.FailoverResult> tryFailover(NodeId failedMasterId,
                                                                    Map<NodeId, Long> replicationOffsets,
                                                                    Map<NodeId, Integer> priorities,
                                                                    long nowMs) {
        Objects.requireNonNull(failedMasterId, "failedMasterId");
        if (!isSubjectivelyDown(failedMasterId, nowMs)) {
            return Optional.empty();
        }

        quorumDecision.recordSdownVote(failedMasterId, sentinelId);
        Set<NodeId> participatingSentinels = new LinkedHashSet<>(peerSentinels);
        participatingSentinels.add(sentinelId);

        Optional<FailoverCoordinator.FailoverResult> result = failoverCoordinator.failover(
                topology,
                failedMasterId,
                quorumDecision.voters(failedMasterId),
                participatingSentinels,
                replicationOffsets,
                priorities,
                nowMs,
                nowMs);

        result.ifPresent(failover -> {
            // 只有当 failover 真正成功时才推进拓扑 epoch，避免半途失败留下错误视图。
            topology.apply(failover.epoch(), failover.nodes());
            quorumDecision.clear(failedMasterId);
        });
        return result;
    }
}
