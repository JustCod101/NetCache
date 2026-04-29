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

public final class ExpirationQueue implements Closeable {
    private static final int MAX_KEYS_PER_TICK = 200;

    private final Queue<ExpirationEntry> entries = new ConcurrentLinkedQueue<>();
    private final Queue<ByteKey> expiredEvents = new ConcurrentLinkedQueue<>();
    private final Timer timer;
    private final long tickMs;
    private final AtomicBoolean closed = new AtomicBoolean();

    public ExpirationQueue(long tickMs) {
        this(new HashedWheelTimer(new NamedThreadFactory(), tickMs, TimeUnit.MILLISECONDS), tickMs);
    }

    ExpirationQueue(Timer timer, long tickMs) {
        if (tickMs <= 0) {
            throw new IllegalArgumentException("tickMs must be positive");
        }
        this.timer = Objects.requireNonNull(timer, "timer");
        this.tickMs = tickMs;
    }

    public void start(BiConsumer<ByteKey, Long> expireCallback) {
        Objects.requireNonNull(expireCallback, "expireCallback");
        scheduleScan(expireCallback);
    }

    public void schedule(ByteKey key, long expireAtMs) {
        Objects.requireNonNull(key, "key");
        if (expireAtMs > 0) {
            entries.add(new ExpirationEntry(key, expireAtMs));
        }
    }

    public ByteKey pollExpiredEvent() {
        return expiredEvents.poll();
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            Set<Timeout> ignored = timer.stop();
            entries.clear();
        }
    }

    private void scheduleScan(BiConsumer<ByteKey, Long> expireCallback) {
        if (closed.get()) {
            return;
        }
        timer.newTimeout(timeout -> {
            scan(expireCallback);
            scheduleScan(expireCallback);
        }, tickMs, TimeUnit.MILLISECONDS);
    }

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
                entries.add(entry);
            }
        }
    }

    private record ExpirationEntry(ByteKey key, long expireAtMs) {
    }

    private static final class NamedThreadFactory implements ThreadFactory {
        private final AtomicInteger counter = new AtomicInteger(1);

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "nc-storage-ttl-" + counter.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        }
    }
}
