package com.netcache.storage.lru;

import com.netcache.common.ByteKey;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * LRU 总索引，相当于 16 条分流车道组成的总调度台。
 * 它把所有 key 分片到多个段里，降低热点锁竞争。
 * 没有这层分段，整个缓存会被单把全局锁卡住，读写越多越挤。
 *
 * <p>上游主要由存储引擎和淘汰策略调用；下游依赖
 * {@link LruSegment} 维护每个分段内的双向链表。</p>
 *
 * <p>线程安全说明：线程安全。并发模型是“按 key 分段 + 每段独立锁”，
 * 跨段淘汰通过原子游标做轮询。</p>
 *
 * <pre>{@code
 * LruIndex index = new LruIndex();
 * index.touch(key);
 * ByteKey victim = index.evictOne();
 * index.remove(key);
 * }</pre>
 */
public final class LruIndex {
    /**
     * 默认分段数。
     * 16 是个折中值，够分流，又不会把结构拆得太碎。
     */
    public static final int SEGMENTS = 16;

    /** 每个槽位各管一段独立 LRU。 */
    private final LruSegment[] segments;
    /**
     * 记录下一次从哪个分段开始找淘汰对象。
     * 这样能避免总是偏爱前几个分段。
     */
    private final AtomicInteger evictionCursor = new AtomicInteger();

    /**
     * 创建默认 16 段的 LRU 索引。
     */
    public LruIndex() {
        this.segments = new LruSegment[SEGMENTS];
        for (int i = 0; i < segments.length; i++) {
            segments[i] = new LruSegment();
        }
    }

    /**
     * 记录某个 key 刚被访问过。
     *
     * @param key 刚发生读写的缓存键
     * @implNote 复杂度平均为 O(1)，实际只会触达 key 所在的那个分段。
     */
    public void touch(ByteKey key) {
        segment(key).touch(key);
    }

    /**
     * 从 LRU 索引中移除一个 key。
     *
     * @param key 已经删除或即将删除的缓存键
     * @implNote 复杂度平均为 O(1)。
     */
    public void remove(ByteKey key) {
        segment(key).remove(key);
    }

    /**
     * 选择一个最合适的淘汰候选。
     *
     * @return 被挑中的 key；如果所有分段都为空则返回 {@code null}
     * @implNote 最坏需要扫过全部 16 个分段，复杂度 O(segments)。
     */
    public ByteKey evictOne() {
        int start = Math.floorMod(evictionCursor.getAndIncrement(), segments.length);
        for (int i = 0; i < segments.length; i++) {
            // 轮转起点，避免淘汰压力长期打在固定分段上。
            ByteKey evicted = segments[(start + i) % segments.length].evictOne();
            if (evicted != null) {
                return evicted;
            }
        }
        return null;
    }

    /**
     * 返回当前 LRU 索引里登记的 key 总数。
     *
     * @return 所有分段大小之和
     * @implNote 复杂度 O(segments)，因为要逐段汇总。
     */
    public int size() {
        int total = 0;
        for (LruSegment segment : segments) {
            total += segment.size();
        }
        return total;
    }

    /**
     * 根据 key 哈希值定位所属分段。
     *
     * @param key 待定位的缓存键
     * @return 负责该 key 的 LRU 分段
     * @implNote 使用 floorMod 是为了兼容负 hashCode，避免数组越界。
     */
    private LruSegment segment(ByteKey key) {
        return segments[Math.floorMod(key.hashCode(), segments.length)];
    }
}
