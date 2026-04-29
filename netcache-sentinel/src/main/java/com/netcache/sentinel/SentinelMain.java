package com.netcache.sentinel;

import java.util.concurrent.CountDownLatch;

/**
 * 哨兵进程入口 —— NetCache 高可用方案的「值班医生」。
 * <p>
 * 想象医院的「值班医生」制度：主任医生（master）病了，其他医生（slave）
 * 要自动顶上来。哨兵就是这个「主任」，负责监控所有节点、主持投票选举、
 * 协调故障转移。整个 failover 流程对客户端透明，恢复时间目标 < 3s。
 * <p>
 * 核心逻辑（健康检查、ODWN 投票、Raft 选举、slave 提升）实现在
 * {@code netcache-cluster} 模块的 {@code SentinelNode} 等类中。
 * 本类只做两件事：读取配置参数、进入等待状态。
 * <p>
 * 线程安全：不可变单例，无需多线程保护。
 * <p>
 * 用例：
 * <pre>
 *   java -Dnetcache.sentinel.id=sentinel-1 \
 *         -Dnetcache.sentinel.quorum=2 \
 *         -cp netcache-sentinel.jar \
 *         com.netcache.sentinel.SentinelMain
 * </pre>
 */
public final class SentinelMain {
    private SentinelMain() {
    }

    public static void main(String[] args) throws InterruptedException {
        // 读取系统属性配置（可由启动脚本传入）
        String sentinelId = System.getProperty("netcache.sentinel.id", "sentinel-1");
        int quorum = Integer.getInteger("netcache.sentinel.quorum", 2);

        System.out.println("Sentinel process started: id=" + sentinelId + ", quorum=" + quorum);

        // TODO: 实例化 SentinelNode，注入 HealthChecker、QuorumDecision 等组件
        // TODO: 启动网络监听，开始向 master/slave 发送 PING
        // TODO: 收到 SDOWN/ODOWN 事件后触发 tryFailover

        // 永久等待——真正的调度由内部线程处理
        new CountDownLatch(1).await();
    }
}
