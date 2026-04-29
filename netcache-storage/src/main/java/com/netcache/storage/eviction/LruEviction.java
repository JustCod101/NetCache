package com.netcache.storage.eviction;

import com.netcache.common.ByteKey;
import com.netcache.storage.lru.LruIndex;

import java.util.Objects;

public final class LruEviction implements EvictionPolicy {
    private final LruIndex lruIndex;

    public LruEviction(LruIndex lruIndex) {
        this.lruIndex = Objects.requireNonNull(lruIndex, "lruIndex");
    }

    @Override
    public ByteKey evictOne() {
        return lruIndex.evictOne();
    }
}
