package com.netcache.cluster.sentinel;

import com.netcache.common.NodeId;

import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 法定票数决策器 —— Sentinel 共识中的「记票员」。
 * <p>
 * 它负责记录“哪个 Sentinel 认为哪个节点已经 SDOWN”，并据此判断是否达到 ODOWN 所需票数。
 * 没有它，故障切换只能依赖单点判断，无法避免误判带来的错误主切。
 * <p>
 * 协作关系：上游由 {@link SentinelNode} 和故障协商流程写入投票；下游由
 * {@link FailoverCoordinator} 或调用方读取票数结果。
 * <p>
 * 线程安全：线程安全。目标节点映射与投票集合都使用并发容器，适合多 Sentinel 观察结果并发汇聚。
 * <p>
 * 典型用例：
 * <pre>{@code
 * decision.recordSdownVote(masterId, sentinelA);
 * decision.recordSdownVote(masterId, sentinelB);
 * boolean odown = decision.reachesObjectiveDown(masterId, 2);
 * }</pre>
 */
public final class QuorumDecision {
    /** 每个目标节点对应一组投票 Sentinel。 */
    private final ConcurrentHashMap<NodeId, Set<NodeId>> votes = new ConcurrentHashMap<>();

    /**
     * 记录一张 SDOWN 票。
     *
     * @param targetNodeId 被判定下线的目标节点
     * @param sentinelId 投出该票的 Sentinel ID
     * @throws NullPointerException 当参数为 {@code null} 时抛出
     * @implNote 采用 Set 存票，同一 Sentinel 重复上报不会被重复计数。
     */
    public void recordSdownVote(NodeId targetNodeId, NodeId sentinelId) {
        Objects.requireNonNull(targetNodeId, "targetNodeId");
        Objects.requireNonNull(sentinelId, "sentinelId");
        votes.computeIfAbsent(targetNodeId, ignored -> ConcurrentHashMap.newKeySet()).add(sentinelId);
    }

    /**
     * 判断某目标节点是否达到 ODOWN 所需票数。
     *
     * @param targetNodeId 目标节点 ID
     * @param quorum 法定票数
     * @return {@code true} 表示投票数达到或超过 quorum
     * @throws IllegalArgumentException 当 {@code quorum <= 0} 时抛出
     * @implNote ODOWN 是票数概念，不关心票的先后顺序，只看去重后的参与 Sentinel 数量。
     */
    public boolean reachesObjectiveDown(NodeId targetNodeId, int quorum) {
        if (quorum <= 0) {
            throw new IllegalArgumentException("quorum must be > 0");
        }
        return voters(targetNodeId).size() >= quorum;
    }

    /**
     * 返回某目标节点当前的全部投票 Sentinel。
     *
     * @param targetNodeId 目标节点 ID
     * @return 只读投票集合；若尚无投票则返回空集合
     * @throws NullPointerException 当 {@code targetNodeId} 为 {@code null} 时抛出
     * @implNote 返回不可修改视图，防止外部跳过记票逻辑直接篡改内部集合。
     */
    public Set<NodeId> voters(NodeId targetNodeId) {
        Objects.requireNonNull(targetNodeId, "targetNodeId");
        return Collections.unmodifiableSet(votes.getOrDefault(targetNodeId, Set.of()));
    }

    /**
     * 清理某目标节点的全部投票状态。
     *
     * @param targetNodeId 目标节点 ID
     * @throws NullPointerException 当 {@code targetNodeId} 为 {@code null} 时抛出
     * @implNote 一次 failover 完成后应清票，避免旧观察结果污染下一轮判定。
     */
    public void clear(NodeId targetNodeId) {
        Objects.requireNonNull(targetNodeId, "targetNodeId");
        votes.remove(targetNodeId);
    }
}
