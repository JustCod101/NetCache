package com.netcache.storage.eviction;

import com.netcache.common.ByteKey;
import com.netcache.storage.lru.LruIndex;

import java.util.Objects;

public final class TtlPriorityEviction implements EvictionPolicy {
    private final LruIndex lruIndex;

    public TtlPriorityEviction(LruIndex lruIndex) {
        this.lruIndex = Objects.requireNonNull(lruIndex, "lruIndex");
    }

    @Override
    public ByteKey evictOne() {
        return lruIndex.evictOne();
    }
}
