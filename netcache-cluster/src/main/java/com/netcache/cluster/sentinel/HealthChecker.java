package com.netcache.cluster.sentinel;

import com.netcache.common.NodeId;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 健康检查器 —— Sentinel 的「体温计」。
 * <p>
 * 它只做一件事：记录最近一次健康心跳，并根据时间窗口判断某节点是否已经进入 SDOWN。没有它，
 * Sentinel 就缺少稳定且可重复的主观下线判定基线。
 * <p>
 * 协作关系：上游由 {@link SentinelNode} 记录 ping 结果并查询状态；内部只依赖并发 Map 保存最近健康时间。
 * <p>
 * 线程安全：线程安全。底层使用 {@link ConcurrentHashMap} 保存时间戳，适合被多个探活线程并发写入。
 * <p>
 * 典型用例：
 * <pre>{@code
 * checker.recordPing(masterId, true, nowMs);
 * boolean sdown = checker.isSubjectivelyDown(masterId, nowMs + 10_000);
 * }</pre>
 */
public final class HealthChecker {
    /** 建议的探活周期，仅用于调度层参考。 */
    private final long pingIntervalMs;
    /** 超过该时间未见健康心跳则视为主观下线。 */
    private final long sdownAfterMs;
    /** 每个节点最近一次健康响应时间。 */
    private final ConcurrentHashMap<NodeId, Long> lastHealthyAt = new ConcurrentHashMap<>();

    /**
     * 创建健康检查器。
     *
     * @param pingIntervalMs 建议探活间隔
     * @param sdownAfterMs 主观下线阈值
     * @throws IllegalArgumentException 当任一时间参数小于等于 0 时抛出
     * @implNote 这里不主动调度 ping，只提供判定规则；真正的探活节奏由外层循环控制。
     */
    public HealthChecker(long pingIntervalMs, long sdownAfterMs) {
        if (pingIntervalMs <= 0) {
            throw new IllegalArgumentException("pingIntervalMs must be > 0");
        }
        if (sdownAfterMs <= 0) {
            throw new IllegalArgumentException("sdownAfterMs must be > 0");
        }
        this.pingIntervalMs = pingIntervalMs;
        this.sdownAfterMs = sdownAfterMs;
    }

    /**
     * 返回建议探活间隔。
     *
     * @return 探活间隔毫秒数
     * @implNote 该值本身不驱动调度，但可供上层调度器读取配置。
     */
    public long pingIntervalMs() {
        return pingIntervalMs;
    }

    /**
     * 返回 SDOWN 判定阈值。
     *
     * @return 主观下线阈值毫秒数
     * @implNote 该值越小故障探测越敏感，但误判概率也越高。
     */
    public long sdownAfterMs() {
        return sdownAfterMs;
    }

    /**
     * 记录一次探活结果。
     *
     * @param nodeId 被探测节点 ID
     * @param healthy 本次探测是否成功
     * @param nowMs 当前时间戳（毫秒）
     * @throws NullPointerException 当 {@code nodeId} 为 {@code null} 时抛出
     * @implNote 仅当探活成功时才刷新时间戳，是为了让连续失败自然累积成超时窗口。
     */
    public void recordPing(NodeId nodeId, boolean healthy, long nowMs) {
        Objects.requireNonNull(nodeId, "nodeId");
        if (healthy) {
            lastHealthyAt.put(nodeId, nowMs);
        }
    }

    /**
     * 判断某节点是否进入主观下线状态。
     *
     * @param nodeId 目标节点 ID
     * @param nowMs 当前时间戳（毫秒）
     * @return {@code true} 表示当前 Sentinel 认为其已主观下线
     * @throws NullPointerException 当 {@code nodeId} 为 {@code null} 时抛出
     * @implNote 从未记录过健康心跳的节点会直接视为下线，这是为了让新目标必须先证明自己健康。
     */
    public boolean isSubjectivelyDown(NodeId nodeId, long nowMs) {
        Objects.requireNonNull(nodeId, "nodeId");
        Long lastHealthy = lastHealthyAt.get(nodeId);
        return lastHealthy == null || nowMs - lastHealthy >= sdownAfterMs;
    }
}
