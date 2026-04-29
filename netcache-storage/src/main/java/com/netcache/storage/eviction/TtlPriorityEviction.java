package com.netcache.storage.eviction;

import com.netcache.common.ByteKey;
import com.netcache.storage.lru.LruIndex;

import java.util.Objects;

/**
 * 预留给“TTL 优先”方向的淘汰策略入口。
 * 眼下实现还和 LRU 一样，但类名先把扩展点站住了。
 * 这样以后如果要优先清理快过期的数据，不必改动调用方协议。
 *
 * <p>上游同样由存储引擎在高水位时调用；当前下游仍委托
 * {@link LruIndex} 选出候选键。</p>
 *
 * <p>线程安全说明：本类本身无可变状态；线程安全性来自底层
 * {@link LruIndex}。</p>
 *
 * <pre>{@code
 * EvictionPolicy policy = new TtlPriorityEviction(index);
 * ByteKey victim = policy.evictOne();
 * }</pre>
 */
public final class TtlPriorityEviction implements EvictionPolicy {
    /** 当前仍复用 LRU 索引提供候选。 */
    private final LruIndex lruIndex;

    /**
     * 创建一个 TTL 优先策略实例。
     *
     * @param lruIndex 当前阶段用于提供淘汰候选的索引
     * @throws NullPointerException 当 {@code lruIndex} 为 {@code null}
     *     时抛出
     */
    public TtlPriorityEviction(LruIndex lruIndex) {
        this.lruIndex = Objects.requireNonNull(lruIndex, "lruIndex");
    }

    /**
     * 选出一个淘汰候选。
     *
     * @return 候选 key；当前实现下等价于 LRU 候选，空索引返回
     *     {@code null}
     * @implNote 这里先保持和 LRU 一致，是为了保住接口稳定性，
     *     后续再逐步替换内部规则。
     */
    @Override
    public ByteKey evictOne() {
        return lruIndex.evictOne();
    }
}
