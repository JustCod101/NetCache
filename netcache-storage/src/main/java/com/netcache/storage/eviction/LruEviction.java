package com.netcache.storage.eviction;

import com.netcache.common.ByteKey;
import com.netcache.storage.lru.LruIndex;

import java.util.Objects;

/**
 * 基于 LRU 的淘汰策略，是当前存储层的默认裁判。
 * 它的规则很朴素：谁最久没被碰过，谁先离场。
 * 没有它，内存高水位时就没有统一的让路机制。
 *
 * <p>上游由 {@code StorageEngine} 在高水位写入前触发；
 * 下游依赖 {@link LruIndex} 产出最冷 key。</p>
 *
 * <p>线程安全说明：本类本身无可变状态；线程安全性来自底层
 * {@link LruIndex}。</p>
 *
 * <pre>{@code
 * LruEviction eviction = new LruEviction(index);
 * ByteKey victim = eviction.evictOne();
 * }</pre>
 */
public final class LruEviction implements EvictionPolicy {
    /** LRU 索引，负责挑出最久未访问的 key。 */
    private final LruIndex lruIndex;

    /**
     * 创建一个基于 LRU 的淘汰策略。
     *
     * @param lruIndex 提供淘汰候选的 LRU 索引
     * @throws NullPointerException 当 {@code lruIndex} 为 {@code null}
     *     时抛出
     */
    public LruEviction(LruIndex lruIndex) {
        this.lruIndex = Objects.requireNonNull(lruIndex, "lruIndex");
    }

    /**
     * 选择一个 LRU 候选键。
     *
     * @return 最久未访问的 key；如果索引为空则返回 {@code null}
     * @implNote 这里不直接删数据，只负责挑人，真正删除由上层做，
     *     这样策略层不会和存储结构绑死。
     */
    @Override
    public ByteKey evictOne() {
        return lruIndex.evictOne();
    }
}
