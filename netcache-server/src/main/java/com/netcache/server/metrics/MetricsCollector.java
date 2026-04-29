package com.netcache.server.metrics;

import java.util.concurrent.atomic.LongAdder;

/**
 * 收集服务端轻量指标的计数器组件，像里程表一样持续累计系统处理过的请求数。
 *
 * <p>这个类解决的是并发环境下的低开销请求计数问题：相比单个原子 long，{@link LongAdder} 在高并发下
 * 具有更好的热点分散能力。如果没有它，后续接入 INFO、监控或压测统计时就缺少统一的计量入口。
 *
 * <p>上游通常是网络分发器或更高层统计切面；下游依赖 JDK 并发工具 {@link LongAdder}。
 *
 * <p>线程安全说明：该类是线程安全的，适合被多个 Netty worker 线程并发调用。
 *
 * <p>典型用例：
 * <pre>{@code
 * MetricsCollector metrics = new MetricsCollector();
 * metrics.recordRequest();
 * long count = metrics.requestCount();
 * }</pre>
 */
public final class MetricsCollector {
    /** 使用 LongAdder 降低并发自增时的共享热点。 */
    private final LongAdder requests = new LongAdder();

    /**
     * 记录一次新请求到达。
     *
     * @implNote 只做自增，不附带标签或维度信息，保持该组件轻量且易于在热路径使用。
     */
    public void recordRequest() {
        requests.increment();
    }

    /**
     * 返回当前累计的请求总数。
     *
     * @return 截止当前时刻的请求计数快照
     * @implNote {@link LongAdder#sum()} 返回的是近实时聚合值，适合监控与统计，不保证与并发更新严格同步。
     */
    public long requestCount() {
        return requests.sum();
    }
}
