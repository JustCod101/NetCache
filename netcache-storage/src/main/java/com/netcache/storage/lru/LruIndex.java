package com.netcache.storage.lru;

import com.netcache.common.ByteKey;

import java.util.concurrent.atomic.AtomicInteger;

public final class LruIndex {
    public static final int SEGMENTS = 16;

    private final LruSegment[] segments;
    private final AtomicInteger evictionCursor = new AtomicInteger();

    public LruIndex() {
        this.segments = new LruSegment[SEGMENTS];
        for (int i = 0; i < segments.length; i++) {
            segments[i] = new LruSegment();
        }
    }

    public void touch(ByteKey key) {
        segment(key).touch(key);
    }

    public void remove(ByteKey key) {
        segment(key).remove(key);
    }

    public ByteKey evictOne() {
        int start = Math.floorMod(evictionCursor.getAndIncrement(), segments.length);
        for (int i = 0; i < segments.length; i++) {
            ByteKey evicted = segments[(start + i) % segments.length].evictOne();
            if (evicted != null) {
                return evicted;
            }
        }
        return null;
    }

    public int size() {
        int total = 0;
        for (LruSegment segment : segments) {
            total += segment.size();
        }
        return total;
    }

    private LruSegment segment(ByteKey key) {
        return segments[Math.floorMod(key.hashCode(), segments.length)];
    }
}
