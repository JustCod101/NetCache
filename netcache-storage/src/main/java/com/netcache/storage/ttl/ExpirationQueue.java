package com.netcache.storage.ttl;

import com.netcache.common.ByteKey;
import io.netty.util.HashedWheelTimer;
import io.netty.util.Timeout;
import io.netty.util.Timer;

import java.io.Closeable;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;

/**
 * TTL 过期调度队列，相当于缓存世界里的闹钟总机。
 * 它负责把“某个 key 在某时过期”这件事延后提醒出来。
 * 没有它，存储层只能在读写时被动清理，冷数据会一直占着内存。
 *
 * <p>上游由 {@code StorageEngine#set/expire} 安排过期；
 * 下游依赖 Netty 的 {@link HashedWheelTimer} 周期扫描。</p>
 *
 * <p>线程安全说明：线程安全。并发模型是无锁队列 + 单个定时器线程
 * 周期扫描，关闭动作通过原子位防重入。</p>
 *
 * <pre>{@code
 * ExpirationQueue queue = new ExpirationQueue(100);
 * queue.start((key, expireAt) -> System.out.println(key));
 * queue.schedule(key, System.currentTimeMillis() + 1000);
 * }</pre>
 */
public final class ExpirationQueue implements Closeable {
    /**
     * 单次扫描最多处理的过期 key 数。
     * 这样做是为了给每个 tick 设上限，避免一次扫太久。
     */
    private static final int MAX_KEYS_PER_TICK = 200;

    /** 待检查的过期条目队列。 */
    private final Queue<ExpirationEntry> entries = new ConcurrentLinkedQueue<>();
    /** 已过期事件队列，便于外部按需拉取。 */
    private final Queue<ByteKey> expiredEvents = new ConcurrentLinkedQueue<>();
    /** 真正负责定时触发的时间轮。 */
    private final Timer timer;
    /** 扫描 tick 间隔，单位毫秒。 */
    private final long tickMs;
    /** 标记是否已经关闭，防止重复 stop。 */
    private final AtomicBoolean closed = new AtomicBoolean();

    /**
     * 创建一个基于 HashedWheelTimer 的过期队列。
     *
     * @param tickMs 时间轮每次推进的间隔，单位毫秒
     * @throws IllegalArgumentException 当 {@code tickMs <= 0} 时抛出
     */
    public ExpirationQueue(long tickMs) {
        this(new HashedWheelTimer(new NamedThreadFactory(), tickMs, TimeUnit.MILLISECONDS), tickMs);
    }

    /**
     * 创建一个可注入定时器的过期队列。
     *
     * @param timer 实际执行定时任务的计时器
     * @param tickMs 时间轮扫描间隔，单位毫秒
     * @throws IllegalArgumentException 当 {@code tickMs <= 0} 时抛出
     * @throws NullPointerException 当 {@code timer} 为 {@code null} 时抛出
     */
    ExpirationQueue(Timer timer, long tickMs) {
        if (tickMs <= 0) {
            throw new IllegalArgumentException("tickMs must be positive");
        }
        this.timer = Objects.requireNonNull(timer, "timer");
        this.tickMs = tickMs;
    }

    /**
     * 启动后台扫描循环。
     *
     * @param expireCallback 发现候选过期项时的回调，参数分别是 key 和
     *     本次条目的绝对过期时间
     * @throws NullPointerException 当回调为 {@code null} 时抛出
     * @implNote 这里只负责不断派发“可能过期”的提醒；真正删不删，
     *     由上层再比对版本时间决定。
     */
    public void start(BiConsumer<ByteKey, Long> expireCallback) {
        Objects.requireNonNull(expireCallback, "expireCallback");
        scheduleScan(expireCallback);
    }

    /**
     * 安排一个 key 在未来某刻参与过期扫描。
     *
     * @param key 要登记 TTL 的缓存键
     * @param expireAtMs 绝对过期时间；小于等于 0 表示不加入队列
     * @throws NullPointerException 当 {@code key} 为 {@code null} 时抛出
     * @implNote 复杂度平均 O(1)。永不过期的 key 不入队，省掉无意义扫描。
     */
    public void schedule(ByteKey key, long expireAtMs) {
        Objects.requireNonNull(key, "key");
        if (expireAtMs > 0) {
            entries.add(new ExpirationEntry(key, expireAtMs));
        }
    }

    /**
     * 拉取一个已过期事件。
     *
     * @return 一个已过期 key；如果当前没有事件则返回 {@code null}
     */
    public ByteKey pollExpiredEvent() {
        return expiredEvents.poll();
    }

    /**
     * 关闭过期队列并停止后台计时器。
     *
     * @implNote 关闭后会清掉待处理队列，但不会替调用方追补未处理事件。
     */
    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            Set<Timeout> ignored = timer.stop();
            entries.clear();
        }
    }

    /**
     * 安排下一次扫描任务。
     *
     * @param expireCallback 发现候选过期项时调用的回调
     * @implNote 每次扫描结束后再递归调度下一次，而不是一次性提交
     *     无限循环任务，这样更容易在 close 时自然停下来。
     */
    private void scheduleScan(BiConsumer<ByteKey, Long> expireCallback) {
        if (closed.get()) {
            return;
        }
        timer.newTimeout(timeout -> {
            scan(expireCallback);
            scheduleScan(expireCallback);
        }, tickMs, TimeUnit.MILLISECONDS);
    }

    /**
     * 扫描到期条目，并把事件抛给上层。
     *
     * @param expireCallback 发现候选过期项时调用的回调
     * @implNote 单次只处理一部分，是为了把“过期清理延迟”控制在
     *     可接受范围内，而不是把整个定时线程拖成大停顿。
     */
    private void scan(BiConsumer<ByteKey, Long> expireCallback) {
        long nowMs = System.currentTimeMillis();
        int processed = 0;
        int size = entries.size();
        while (processed < MAX_KEYS_PER_TICK && size-- > 0) {
            ExpirationEntry entry = entries.poll();
            if (entry == null) {
                return;
            }
            if (entry.expireAtMs <= nowMs) {
                expireCallback.accept(entry.key, entry.expireAtMs);
                expiredEvents.add(entry.key);
                processed++;
            } else {
                // 未到期的条目重新入队，等后续 tick 再看。
                entries.add(entry);
            }
        }
    }

    /**
     * 过期队列中的最小登记单元。
     * 一个 key 可能出现多次，因为同一键可以多次续期。
     * 真正删除前还要靠 expireAtMs 做版本比对。
     */
    private record ExpirationEntry(ByteKey key, long expireAtMs) {
    }

    /**
     * 给 TTL 线程起一个容易识别的名字。
     * 出问题时看线程栈会更直观。
     */
    private static final class NamedThreadFactory implements ThreadFactory {
        /** 用于给线程名递增编号。 */
        private final AtomicInteger counter = new AtomicInteger(1);

        /**
         * 创建后台守护线程。
         *
         * @param runnable 线程实际要执行的任务
         * @return 命名后的守护线程
         */
        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "nc-storage-ttl-" + counter.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        }
    }
}
