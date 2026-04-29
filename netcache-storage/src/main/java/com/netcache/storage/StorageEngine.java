package com.netcache.storage;

import com.netcache.common.ByteKey;
import com.netcache.common.exception.OomGuardException;
import com.netcache.common.exception.StorageException;
import com.netcache.storage.eviction.EvictionPolicy;
import com.netcache.storage.eviction.LruEviction;
import com.netcache.storage.lru.LruIndex;
import com.netcache.storage.memory.MemoryWatermark;
import com.netcache.storage.ttl.ExpirationQueue;

import java.io.Closeable;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 存储引擎是 netcache-storage 的心脏，像总控台一样协调数据、TTL、LRU
 * 和内存保护。
 * 它把一个个分散组件拧成可用的 KV 存储，对外提供 GET/SET/INCR
 * 这类核心能力。
 * 没有它，上层协议层就得自己拼接 Map、过期扫描、淘汰和拒写逻辑，
 * 复杂度会直接炸开。
 *
 * <p>上游通常是命令执行器或服务端处理器；下游依赖
 * {@link ConcurrentHashMap}、{@link LruIndex}、
 * {@link ExpirationQueue}、{@link MemoryWatermark} 和
 * {@link EvictionPolicy}。</p>
 *
 * <p>线程安全说明：线程安全。并发模型是 ConcurrentHashMap 负责
 * 数据并发，LRU 用分段锁，TTL 用后台定时线程，单 key 更新依赖
 * {@code compute}/{@code computeIfPresent} 保证原子替换。</p>
 *
 * <pre>{@code
 * try (StorageEngine engine = new StorageEngine()) {
 *     engine.set(key, "value".getBytes(), Duration.ofSeconds(5));
 *     Optional<byte[]> value = engine.get(key);
 *     long next = engine.incr(counterKey);
 * }
 * }</pre>
 */
public final class StorageEngine implements Closeable {
    /** 约定 0 代表永不过期。 */
    private static final long NO_EXPIRATION = 0L;

    /** 真正保存 key -> value 的主存表。 */
    private final ConcurrentHashMap<ByteKey, StoredValue> map;
    /** 负责维护最近最少使用顺序。 */
    private final LruIndex lruIndex;
    /** 负责后台 TTL 扫描和过期提醒。 */
    private final ExpirationQueue expirationQueue;
    /** 负责判断是否该淘汰或拒写。 */
    private final MemoryWatermark watermark;
    /** 当前使用的淘汰规则。 */
    private final EvictionPolicy evictionPolicy;
    /** 可注入时钟，便于测试固定时间。 */
    private final Clock clock;

    /**
     * 使用默认组件创建一个完整存储引擎。
     */
    public StorageEngine() {
        this(new ConcurrentHashMap<>(), new LruIndex(), new ExpirationQueue(100), MemoryWatermark.defaults(), Clock.systemUTC());
    }

    /**
     * 使用指定水位线策略创建存储引擎。
     *
     * @param watermark 用于判断高水位和危险水位的规则
     */
    public StorageEngine(MemoryWatermark watermark) {
        this(new ConcurrentHashMap<>(), new LruIndex(), new ExpirationQueue(100), watermark, Clock.systemUTC());
    }

    /**
     * 创建一个可完全注入依赖的存储引擎。
     *
     * @param map 底层主存表
     * @param lruIndex LRU 索引
     * @param expirationQueue TTL 调度队列
     * @param watermark 内存水位保护器
     * @param clock 当前时间来源
     * @throws NullPointerException 任一依赖为 {@code null} 时抛出
     * @implNote 构造时立刻启动过期扫描，保证引擎一旦可用，TTL 也同步在线。
     */
    StorageEngine(ConcurrentHashMap<ByteKey, StoredValue> map,
                  LruIndex lruIndex,
                  ExpirationQueue expirationQueue,
                  MemoryWatermark watermark,
                  Clock clock) {
        this.map = Objects.requireNonNull(map, "map");
        this.lruIndex = Objects.requireNonNull(lruIndex, "lruIndex");
        this.expirationQueue = Objects.requireNonNull(expirationQueue, "expirationQueue");
        this.watermark = Objects.requireNonNull(watermark, "watermark");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.evictionPolicy = new LruEviction(lruIndex);
        // 过期回调只按版本删除，避免旧 TTL 任务误删续期后的新值。
        this.expirationQueue.start(this::deleteIfExpiredVersion);
    }

    /**
     * 读取一个 key 对应的值。
     *
     * @param key 要查询的缓存键
     * @return 命中字符串值时返回对应字节数组；命中计数器时返回其 UTF-8
     *     文本表示；未命中、已过期或类型不支持时返回空 Optional
     * @throws NullPointerException 当 {@code key} 为 {@code null} 时抛出
     * @implNote 平均复杂度 O(1)。读取成功时会顺手刷新 LRU 和访问时间；
     *     读到过期值时会直接清掉，避免脏数据反复被命中。
     */
    public Optional<byte[]> get(ByteKey key) {
        Objects.requireNonNull(key, "key");
        StoredValue value = map.computeIfPresent(key, (storedKey, storedValue) -> {
            long nowMs = nowMs();
            if (storedValue.isExpired(nowMs)) {
                // 命中过期值时就地清理，避免冷键一直躺在主表里。
                lruIndex.remove(storedKey);
                return null;
            }
            lruIndex.touch(storedKey);
            return storedValue.withLastAccessMs(nowMs);
        });
        if (value instanceof StringValue stringValue) {
            return Optional.of(stringValue.value());
        }
        if (value instanceof CounterValue counterValue) {
            return Optional.of(Long.toString(counterValue.value()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        return Optional.empty();
    }

    /**
     * 写入一个永不过期的字符串值。
     *
     * @param key 要写入的缓存键
     * @param value 要保存的字节内容
     */
    public void set(ByteKey key, byte[] value) {
        set(key, value, Duration.ZERO);
    }

    /**
     * 写入一个字符串值，并可选设置 TTL。
     *
     * @param key 要写入的缓存键
     * @param value 要保存的字节内容
     * @param ttl 生存时间；零或负数表示永不过期
     * @throws NullPointerException 任一参数为 {@code null} 时抛出
     * @throws OomGuardException 当堆使用率已超过危险水位时抛出
     * @implNote 平均复杂度 O(1)。写入前先做拒写和高水位淘汰，目的不是
     *     绝对避免 OOM，而是把风险前移到可控位置。
     */
    public void set(ByteKey key, byte[] value, Duration ttl) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(ttl, "ttl");
        guardWrite();
        evictIfHighWatermark();
        long nowMs = nowMs();
        long expireAtMs = expireAtMs(ttl, nowMs);
        map.put(key, new StringValue(value, expireAtMs, nowMs));
        lruIndex.touch(key);
        expirationQueue.schedule(key, expireAtMs);
    }

    /**
     * 删除一个 key。
     *
     * @param key 要删除的缓存键
     * @return {@code true} 表示之前确实存在该键；否则返回 {@code false}
     * @throws NullPointerException 当 {@code key} 为 {@code null} 时抛出
     */
    public boolean del(ByteKey key) {
        Objects.requireNonNull(key, "key");
        StoredValue removed = map.remove(key);
        lruIndex.remove(key);
        return removed != null;
    }

    /**
     * 更新某个 key 的 TTL。
     *
     * @param key 要续期或改期的缓存键
     * @param ttl 新的存活时长；零或负数表示取消过期
     * @return {@code true} 表示 key 存在且已成功更新；不存在或已过期
     *     则返回 {@code false}
     * @throws NullPointerException 任一参数为 {@code null} 时抛出
     * @implNote 平均复杂度 O(1)。即使同一 key 被多次 schedule，删除时也会
     *     再次核对版本时间，所以旧定时任务不会误杀新值。
     */
    public boolean expire(ByteKey key, Duration ttl) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(ttl, "ttl");
        long nowMs = nowMs();
        long expireAtMs = expireAtMs(ttl, nowMs);
        StoredValue updated = map.computeIfPresent(key, (storedKey, storedValue) -> {
            if (storedValue.isExpired(nowMs)) {
                // 续期前先把已过期键剔掉，避免“假成功”。
                lruIndex.remove(storedKey);
                return null;
            }
            return storedValue.withExpireAtMs(expireAtMs).withLastAccessMs(nowMs);
        });
        if (updated != null) {
            expirationQueue.schedule(key, expireAtMs);
        }
        return updated != null;
    }

    /**
     * 查询某个 key 剩余 TTL。
     *
     * @param key 要查询的缓存键
     * @return 剩余毫秒数；-1 表示存在但永不过期；-2 表示 key 不存在或已过期
     * @throws NullPointerException 当 {@code key} 为 {@code null} 时抛出
     */
    public long ttl(ByteKey key) {
        Objects.requireNonNull(key, "key");
        long nowMs = nowMs();
        StoredValue value = map.get(key);
        if (value == null || value.isExpired(nowMs)) {
            // 这里复用 del，顺手把主表和 LRU 索引都收干净。
            del(key);
            return -2L;
        }
        if (value.expireAtMs() == NO_EXPIRATION) {
            return -1L;
        }
        return Math.max(0L, value.expireAtMs() - nowMs);
    }

    /**
     * 判断某个 key 当前是否可读。
     *
     * @param key 要检查的缓存键
     * @return {@code true} 表示存在且未过期；否则返回 {@code false}
     * @implNote 这里直接复用 {@link #get(ByteKey)}，让存在性判断和真实读取
     *     共享同一套过期清理逻辑。
     */
    public boolean exists(ByteKey key) {
        return get(key).isPresent();
    }

    /**
     * 对计数器执行加 1。
     *
     * @param key 计数器键
     * @return 自增后的值
     * @throws NullPointerException 当 {@code key} 为 {@code null} 时抛出
     * @throws OomGuardException 当堆使用率已超过危险水位时抛出
     * @throws StorageException 当原值不是整数语义时抛出
     */
    public long incr(ByteKey key) {
        return add(key, 1L);
    }

    /**
     * 对计数器执行减 1。
     *
     * @param key 计数器键
     * @return 自减后的值
     * @throws NullPointerException 当 {@code key} 为 {@code null} 时抛出
     * @throws OomGuardException 当堆使用率已超过危险水位时抛出
     * @throws StorageException 当原值不是整数语义时抛出
     */
    public long decr(ByteKey key) {
        return add(key, -1L);
    }

    /**
     * 返回当前主表中的键数量。
     *
     * @return Map 当前大小；已过期但尚未被扫描掉的键也可能暂时计入
     */
    public int size() {
        return map.size();
    }

    /**
     * 关闭存储引擎并停止后台 TTL 线程。
     */
    @Override
    public void close() {
        expirationQueue.close();
    }

    /**
     * 对计数器执行带符号增量。
     *
     * @param key 计数器键
     * @param delta 增量；正数为加，负数为减
     * @return 更新后的计数值
     * @throws NullPointerException 当 {@code key} 为 {@code null} 时抛出
     * @throws OomGuardException 当堆使用率已超过危险水位时抛出
     * @throws StorageException 当原值不是可解析整数或类型不支持时抛出
     * @throws ArithmeticException 当 long 运算溢出时抛出
     * @implNote 平均复杂度 O(1)。整段更新放进 Map.compute，确保同一 key
     *     的读改写是原子的，不会被并发 incr/decr 撕裂。
     */
    private long add(ByteKey key, long delta) {
        Objects.requireNonNull(key, "key");
        guardWrite();
        evictIfHighWatermark();
        long nowMs = nowMs();
        StoredValue updated = map.compute(key, (storedKey, storedValue) -> {
            if (storedValue == null || storedValue.isExpired(nowMs)) {
                // 不存在或已过期时，直接从 delta 起步，行为更贴近 Redis。
                return new CounterValue(delta, NO_EXPIRATION, nowMs);
            }
            if (storedValue instanceof CounterValue counterValue) {
                return counterValue.add(delta, nowMs);
            }
            if (storedValue instanceof StringValue stringValue) {
                try {
                    // 允许“内容是整数的字符串”无缝升级成计数器。
                    long parsed = Long.parseLong(new String(stringValue.value(), java.nio.charset.StandardCharsets.UTF_8));
                    return new CounterValue(Math.addExact(parsed, delta), stringValue.expireAtMs(), nowMs);
                } catch (NumberFormatException ex) {
                    throw new StorageException("TYPE_MISMATCH", "value is not an integer", ex);
                }
            }
            throw new StorageException("TYPE_MISMATCH", "unsupported stored value type");
        });
        lruIndex.touch(key);
        return ((CounterValue) updated).value();
    }

    /**
     * 在写路径上检查是否已进入拒写区。
     *
     * @throws OomGuardException 当堆使用率已超过危险水位时抛出
     */
    private void guardWrite() {
        if (watermark.isDanger()) {
            throw new OomGuardException("heap usage exceeded danger watermark");
        }
    }

    /**
     * 在高水位时尝试同步淘汰一个键。
     *
     * @implNote 这里只做一次淘汰，而不是循环清到安全线，目的是把单次写入
     *     的阻塞成本控制住；后续写入会继续触发补充淘汰。
     */
    private void evictIfHighWatermark() {
        if (!watermark.isHigh()) {
            return;
        }
        ByteKey evicted = evictionPolicy.evictOne();
        if (evicted != null) {
            map.remove(evicted);
        }
    }

    /**
     * 仅当过期版本仍匹配时才删除 key。
     *
     * @param key 可能已过期的缓存键
     * @param expireAtMs 触发本次回调的过期时间版本
     * @implNote 这是 TTL 正确性的关键保护。因为同一个 key 可能先后被
     *     多次续期，旧任务跑到时必须先核对版本，不能见到 key 就删。
     */
    private void deleteIfExpiredVersion(ByteKey key, long expireAtMs) {
        map.computeIfPresent(key, (storedKey, storedValue) -> {
            if (storedValue.expireAtMs() == expireAtMs && storedValue.isExpired(nowMs())) {
                lruIndex.remove(storedKey);
                return null;
            }
            return storedValue;
        });
    }

    /**
     * 把相对 TTL 换算成绝对过期时间。
     *
     * @param ttl 生存时间
     * @param nowMs 当前时间毫秒值
     * @return 绝对过期时间；零或负 TTL 返回 0，表示永不过期
     * @throws ArithmeticException 当毫秒加法溢出时抛出
     */
    private long expireAtMs(Duration ttl, long nowMs) {
        if (ttl.isZero() || ttl.isNegative()) {
            return NO_EXPIRATION;
        }
        return Math.addExact(nowMs, ttl.toMillis());
    }

    /**
     * 读取当前毫秒时间。
     *
     * @return 当前时钟毫秒值
     */
    private long nowMs() {
        return clock.millis();
    }
}
