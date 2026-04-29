package com.netcache.storage;

public sealed interface StoredValue permits StringValue, CounterValue {
    long expireAtMs();

    long lastAccessMs();

    int sizeBytes();

    StoredValue withLastAccessMs(long lastAccessMs);

    StoredValue withExpireAtMs(long expireAtMs);

    default boolean isExpired(long nowMs) {
        return expireAtMs() > 0 && expireAtMs() <= nowMs;
    }
}
