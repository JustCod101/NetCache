package com.netcache.storage.eviction;

import com.netcache.common.ByteKey;

/**
 * 淘汰策略接口，相当于缓存快满时的裁判规则。
 * 它定义“该赶谁走”，但不关心底层数据如何存放。
 * 没有这层接口，存储引擎会把淘汰算法硬编码死，后续很难替换。
 *
 * <p>上游由存储引擎在高水位时调用；下游实现可以接 LRU、TTL
 * 优先或其他策略。</p>
 *
 * <p>线程安全说明：接口本身无状态；具体是否线程安全取决于实现。
 * 当前实现都委托给线程安全的 LRU 索引。</p>
 *
 * <pre>{@code
 * EvictionPolicy policy = new LruEviction(index);
 * ByteKey victim = policy.evictOne();
 * if (victim != null) {
 *     // 再由上层真正删除数据
 * }
 * }</pre>
 */
public interface EvictionPolicy {
    /**
     * 选出一个应该被淘汰的 key。
     *
     * @return 候选 key；如果当前没有可淘汰对象则返回 {@code null}
     */
    ByteKey evictOne();
}
