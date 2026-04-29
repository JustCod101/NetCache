package com.netcache.benchmark;

import com.netcache.client.NetCacheClient;
import com.netcache.server.ServerConfig;
import com.netcache.server.lifecycle.NodeLifecycle;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Launches a real NetCache server (Netty TCP) and connects a real NetCacheClient
 * over the loopback interface, so that benchmarks exercise the full stack:
 * client encode -> TCP -> server decode -> StorageEngine -> server encode -> TCP -> client decode.
 */
final class LocalBenchmarkNode implements AutoCloseable {
    private final AtomicInteger cursor = new AtomicInteger();
    private final byte[][] keys;
    private final byte[] value;
    private final int poolSize;
    private NodeLifecycle lifecycle;
    private NetCacheClient client;
    private int port;

    LocalBenchmarkNode(int keyCount, int valueSizeBytes, int poolSize) {
        if (keyCount <= 0) {
            throw new IllegalArgumentException("keyCount must be > 0");
        }
        if (valueSizeBytes <= 0) {
            throw new IllegalArgumentException("valueSizeBytes must be > 0");
        }
        this.keys = new byte[keyCount][];
        for (int i = 0; i < keyCount; i++) {
            keys[i] = ("bench-key-" + i).getBytes(StandardCharsets.UTF_8);
        }
        this.value = new byte[valueSizeBytes];
        // fill with printable ASCII to be realistic
        for (int i = 0; i < valueSizeBytes; i++) {
            value[i] = (byte) ('A' + (i % 26));
        }
        this.poolSize = poolSize;
    }

    void start() throws Exception {
        port = findFreePort();
        ServerConfig config = new ServerConfig("127.0.0.1", port, 1, 0);
        lifecycle = new NodeLifecycle(config);
        lifecycle.start();

        client = NetCacheClient.builder()
                .seeds("127.0.0.1:" + port)
                .poolSizePerNode(poolSize)
                .connectTimeout(Duration.ofSeconds(5))
                .readTimeout(Duration.ofSeconds(5))
                .maxRetries(0)
                .build();
        warmUp();
    }

    NetCacheClient client() {
        return client;
    }

    byte[] nextKey() {
        return keys[Math.floorMod(cursor.getAndIncrement(), keys.length)];
    }

    byte[] value() {
        return value;
    }

    int port() {
        return port;
    }

    @Override
    public void close() {
        if (client != null) {
            client.close();
        }
        if (lifecycle != null) {
            lifecycle.stop();
        }
    }

    private void warmUp() {
        for (byte[] key : keys) {
            client.set(key, value);
        }
    }

    private static int findFreePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException("cannot find free port", e);
        }
    }
}
