package com.netcache.cluster.sentinel;

import com.netcache.common.NodeId;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 轻量选举器 —— Sentinel failover 中的「简化版主持人」。
 * <p>
 * 它不是完整 Raft 实现，而是一个为本地化 failover 场景准备的极简 leader 选举器：只在参与者足够时
 * 递增 term，并稳定选出一个 leader。没有它，多 Sentinel 同时触发切换时就缺少统一协调者。
 * <p>
 * 协作关系：上游由 {@link FailoverCoordinator} 调用；下游产出的 {@link ElectionResult} 会用于记录
 * 哪个 Sentinel 在本轮任期中负责主导切换。
 * <p>
 * 线程安全：线程安全。term 通过 {@link AtomicLong} 递增，其余计算都在方法栈内完成。
 * <p>
 * 典型用例：
 * <pre>{@code
 * Optional<ElectionResult> election = raftLite.electLeader(sentinels, 2);
 * election.ifPresent(result -> System.out.println(result.leaderId()));
 * }</pre>
 */
public final class RaftLite {
    /** 当前任期号，每次成功选举都会单调递增。 */
    private final AtomicLong currentTerm = new AtomicLong();

    /**
     * 在参与 Sentinel 中发起一次极简 leader 选举。
     *
     * @param participatingSentinels 参与本轮选举的 Sentinel 集合
     * @param quorum 法定票数
     * @return 若参与者数量满足 quorum，则返回本轮选举结果；否则返回空
     * @throws NullPointerException 当 {@code participatingSentinels} 为 {@code null} 时抛出
     * @throws IllegalArgumentException 当 {@code quorum <= 0} 时抛出
     * @implNote 当前策略是“去重 + 排序后选第一个”为 leader，重点在稳定和可预测，而不是模拟完整 Raft 行为。
     */
    public Optional<ElectionResult> electLeader(Collection<NodeId> participatingSentinels, int quorum) {
        Objects.requireNonNull(participatingSentinels, "participatingSentinels");
        if (quorum <= 0) {
            throw new IllegalArgumentException("quorum must be > 0");
        }
        List<NodeId> voters = participatingSentinels.stream()
                .distinct()
                .sorted(java.util.Comparator.comparing(NodeId::toString))
                .toList();
        if (voters.size() < quorum) {
            return Optional.empty();
        }

        // 选择排序后第一个节点作为 leader，不是为了“最优”，而是为了在本地实验环境中提供稳定、可复现的结果。
        NodeId leaderId = voters.getFirst();
        long term = currentTerm.incrementAndGet();
        Map<NodeId, NodeId> votes = new LinkedHashMap<>();
        for (NodeId voter : voters) {
            votes.put(voter, leaderId);
        }
        return Optional.of(new ElectionResult(term, leaderId, Map.copyOf(votes)));
    }

    /**
     * 单轮选举的结果快照。
     *
     * @param term 本轮任期号
     * @param leaderId 当选 leader 的 Sentinel ID
     * @param votes 每个投票者投给谁的明细
     */
    public record ElectionResult(long term, NodeId leaderId, Map<NodeId, NodeId> votes) {
        /**
         * 校验选举结果中的关键字段。
         *
         * @throws NullPointerException 当 {@code leaderId} 或 {@code votes} 为 {@code null} 时抛出
         * @implNote 明细投票表保留了“谁投给谁”，便于测试中断言选举过程而不只看最终 leader。
         */
        public ElectionResult {
            Objects.requireNonNull(leaderId, "leaderId");
            Objects.requireNonNull(votes, "votes");
        }
    }
}
