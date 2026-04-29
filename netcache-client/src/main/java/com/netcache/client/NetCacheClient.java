package com.netcache.client;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

public interface NetCacheClient extends AutoCloseable {
    byte[] get(byte[] key);

    void set(byte[] key, byte[] value);

    void set(byte[] key, byte[] value, Duration ttl);

    long incr(byte[] key);

    boolean del(byte[] key);

    boolean expire(byte[] key, Duration ttl);

    CompletableFuture<byte[]> getAsync(byte[] key);

    CompletableFuture<Void> setAsync(byte[] key, byte[] value);

    CompletableFuture<Void> setAsync(byte[] key, byte[] value, Duration ttl);

    CompletableFuture<Long> incrAsync(byte[] key);

    CompletableFuture<Boolean> delAsync(byte[] key);

    CompletableFuture<Boolean> expireAsync(byte[] key, Duration ttl);

    @Override
    void close();

    static ClientBuilder builder() {
        return new ClientBuilder();
    }
}
