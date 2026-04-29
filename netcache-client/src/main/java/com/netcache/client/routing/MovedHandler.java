package com.netcache.client.routing;

import com.netcache.cluster.ClusterTopology;

import java.util.Objects;

/**
 * MOVED 场景下的拓扑刷新助手，像收到最新地址通知后的“改投员”，负责把新的集群视图写回本地缓存。
 * <p>
 * 之所以需要它，是因为集群主从切换或槽位迁移后，旧路由可能立即失效；如果没有这层集中刷新逻辑，客户端各处就得自己直接改拓扑缓存，职责会分散。
 * <p>
 * 上游通常由处理 MOVED/重定向结果的逻辑调用；下游只依赖 {@link TopologyCache} 来保存新的拓扑快照。
 * <p>
 * 线程安全说明：该类本身是线程安全的，只持有一个不可变引用；真正的并发可见性由 {@link TopologyCache} 内部的原子引用保证。
 * <p>
 * 典型用例：
 * <pre>{@code
 * MovedHandler movedHandler = new MovedHandler(topologyCache);
 * movedHandler.refresh(newTopology);
 * String newSeed = topologyCache.route(key);
 * }</pre>
 */
public final class MovedHandler {
    private final TopologyCache topologyCache;

    /**
     * 创建一个 MOVED 拓扑刷新处理器。
     *
     * @param topologyCache 表示要被更新的本地拓扑缓存，不能为空
     * @throws NullPointerException 当 {@code topologyCache} 为 {@code null} 时抛出
     * @implNote 当前实现只负责写入新拓扑，不承担远端拓扑拉取职责。
     */
    public MovedHandler(TopologyCache topologyCache) {
        this.topologyCache = Objects.requireNonNull(topologyCache, "topologyCache");
    }

    /**
     * 刷新本地保存的集群拓扑。
     *
     * @param topology 表示新的集群拓扑快照，不能为空
     * @throws NullPointerException 当 {@code topology} 为 {@code null} 时由下游缓存校验触发
     * @implNote 当前方法是同步原子更新；调用完成后，新请求即可按新拓扑继续路由。
     */
    public void refresh(ClusterTopology topology) {
        topologyCache.updateTopology(topology);
    }
}
