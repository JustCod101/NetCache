package com.netcache.storage;

public record CounterValue(long value, long expireAtMs, long lastAccessMs) implements StoredValue {
    @Override
    public int sizeBytes() {
        return Long.BYTES;
    }

    @Override
    public CounterValue withLastAccessMs(long lastAccessMs) {
        return new CounterValue(value, expireAtMs, lastAccessMs);
    }

    @Override
    public CounterValue withExpireAtMs(long expireAtMs) {
        return new CounterValue(value, expireAtMs, lastAccessMs);
    }

    public CounterValue add(long delta, long nowMs) {
        return new CounterValue(Math.addExact(value, delta), expireAtMs, nowMs);
    }
}
