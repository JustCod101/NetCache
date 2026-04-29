package com.netcache.storage;

import java.util.Objects;

public record StringValue(byte[] value, long expireAtMs, long lastAccessMs) implements StoredValue {
    public StringValue {
        value = Objects.requireNonNull(value, "value").clone();
    }

    @Override
    public byte[] value() {
        return value.clone();
    }

    @Override
    public int sizeBytes() {
        return value.length;
    }

    @Override
    public StringValue withLastAccessMs(long lastAccessMs) {
        return new StringValue(value, expireAtMs, lastAccessMs);
    }

    @Override
    public StringValue withExpireAtMs(long expireAtMs) {
        return new StringValue(value, expireAtMs, lastAccessMs);
    }
}
