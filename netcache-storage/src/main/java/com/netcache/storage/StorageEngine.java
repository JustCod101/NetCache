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

public final class StorageEngine implements Closeable {
    private static final long NO_EXPIRATION = 0L;

    private final ConcurrentHashMap<ByteKey, StoredValue> map;
    private final LruIndex lruIndex;
    private final ExpirationQueue expirationQueue;
    private final MemoryWatermark watermark;
    private final EvictionPolicy evictionPolicy;
    private final Clock clock;

    public StorageEngine() {
        this(new ConcurrentHashMap<>(), new LruIndex(), new ExpirationQueue(100), MemoryWatermark.defaults(), Clock.systemUTC());
    }

    public StorageEngine(MemoryWatermark watermark) {
        this(new ConcurrentHashMap<>(), new LruIndex(), new ExpirationQueue(100), watermark, Clock.systemUTC());
    }

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
        this.expirationQueue.start(this::deleteIfExpiredVersion);
    }

    public Optional<byte[]> get(ByteKey key) {
        Objects.requireNonNull(key, "key");
        StoredValue value = map.computeIfPresent(key, (storedKey, storedValue) -> {
            long nowMs = nowMs();
            if (storedValue.isExpired(nowMs)) {
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

    public void set(ByteKey key, byte[] value) {
        set(key, value, Duration.ZERO);
    }

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

    public boolean del(ByteKey key) {
        Objects.requireNonNull(key, "key");
        StoredValue removed = map.remove(key);
        lruIndex.remove(key);
        return removed != null;
    }

    public boolean expire(ByteKey key, Duration ttl) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(ttl, "ttl");
        long nowMs = nowMs();
        long expireAtMs = expireAtMs(ttl, nowMs);
        StoredValue updated = map.computeIfPresent(key, (storedKey, storedValue) -> {
            if (storedValue.isExpired(nowMs)) {
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

    public long ttl(ByteKey key) {
        Objects.requireNonNull(key, "key");
        long nowMs = nowMs();
        StoredValue value = map.get(key);
        if (value == null || value.isExpired(nowMs)) {
            del(key);
            return -2L;
        }
        if (value.expireAtMs() == NO_EXPIRATION) {
            return -1L;
        }
        return Math.max(0L, value.expireAtMs() - nowMs);
    }

    public boolean exists(ByteKey key) {
        return get(key).isPresent();
    }

    public long incr(ByteKey key) {
        return add(key, 1L);
    }

    public long decr(ByteKey key) {
        return add(key, -1L);
    }

    public int size() {
        return map.size();
    }

    @Override
    public void close() {
        expirationQueue.close();
    }

    private long add(ByteKey key, long delta) {
        Objects.requireNonNull(key, "key");
        guardWrite();
        evictIfHighWatermark();
        long nowMs = nowMs();
        StoredValue updated = map.compute(key, (storedKey, storedValue) -> {
            if (storedValue == null || storedValue.isExpired(nowMs)) {
                return new CounterValue(delta, NO_EXPIRATION, nowMs);
            }
            if (storedValue instanceof CounterValue counterValue) {
                return counterValue.add(delta, nowMs);
            }
            if (storedValue instanceof StringValue stringValue) {
                try {
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

    private void guardWrite() {
        if (watermark.isDanger()) {
            throw new OomGuardException("heap usage exceeded danger watermark");
        }
    }

    private void evictIfHighWatermark() {
        if (!watermark.isHigh()) {
            return;
        }
        ByteKey evicted = evictionPolicy.evictOne();
        if (evicted != null) {
            map.remove(evicted);
        }
    }

    private void deleteIfExpiredVersion(ByteKey key, long expireAtMs) {
        map.computeIfPresent(key, (storedKey, storedValue) -> {
            if (storedValue.expireAtMs() == expireAtMs && storedValue.isExpired(nowMs())) {
                lruIndex.remove(storedKey);
                return null;
            }
            return storedValue;
        });
    }

    private long expireAtMs(Duration ttl, long nowMs) {
        if (ttl.isZero() || ttl.isNegative()) {
            return NO_EXPIRATION;
        }
        return Math.addExact(nowMs, ttl.toMillis());
    }

    private long nowMs() {
        return clock.millis();
    }
}
