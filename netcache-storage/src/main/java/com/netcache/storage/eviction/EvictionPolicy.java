package com.netcache.storage.eviction;

import com.netcache.common.ByteKey;

public interface EvictionPolicy {
    ByteKey evictOne();
}
