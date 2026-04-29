package com.netcache.benchmark;

import com.netcache.client.NetCacheClient;
import com.netcache.client.pool.NodeChannel;
import com.netcache.protocol.OpCode;
import com.netcache.protocol.ResultType;
import com.netcache.protocol.Status;
import com.netcache.protocol.command.Request;
import com.netcache.protocol.command.Response;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

final class LocalBenchmarkNode implements AutoCloseable {
    private final AtomicInteger cursor = new AtomicInteger();
    private final byte[][] keys;
    private final byte[] value;
    private final InMemoryBackend backend = new InMemoryBackend();
    private NetCacheClient client;

    LocalBenchmarkNode(int keyCount, int valueSizeBytes) {
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
        this.value = "x".repeat(valueSizeBytes).getBytes(StandardCharsets.UTF_8);
    }

    void start() {
        client = NetCacheClient.builder()
                .seeds("local:1")
                .poolSizePerNode(4)
                .maxRetries(1)
                .channelFactory((seed, index) -> new InMemoryNodeChannel(backend))
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

    @Override
    public void close() {
        if (client != null) {
            client.close();
        }
    }

    private void warmUp() {
        for (byte[] key : keys) {
            client.set(key, value);
        }
    }

    private static byte[] int64(long value) {
        return ByteBuffer.allocate(Long.BYTES).putLong(value).array();
    }

    private static final class InMemoryBackend {
        private final ConcurrentHashMap<String, byte[]> values = new ConcurrentHashMap<>();
    }

    private static final class InMemoryNodeChannel implements NodeChannel {
        private final InMemoryBackend backend;

        private InMemoryNodeChannel(InMemoryBackend backend) {
            this.backend = backend;
        }

        @Override
        public CompletableFuture<Response> send(Request request) {
            java.util.List<byte[]> args = request.args();
            Response response = switch (request.opCode()) {
                case GET -> {
                    byte[] found = backend.values.get(key(args.get(0)));
                    yield found == null
                            ? new Response(Status.NIL, ResultType.NULL, new byte[0], request.requestId())
                            : new Response(Status.OK, ResultType.BYTES, found, request.requestId());
                }
                case SET -> {
                    backend.values.put(key(args.get(0)), args.get(1).clone());
                    yield new Response(Status.OK, ResultType.NULL, new byte[0], request.requestId());
                }
                case DEL -> new Response(Status.OK, ResultType.INT64,
                        int64(backend.values.remove(key(args.get(0))) == null ? 0L : 1L),
                        request.requestId());
                case EXPIRE -> new Response(Status.OK, ResultType.INT64,
                        int64(backend.values.containsKey(key(args.get(0))) ? 1L : 0L),
                        request.requestId());
                case INCR -> {
                    byte[] updated = backend.values.compute(key(args.get(0)), (ignored, current) -> {
                        long currentValue = current == null ? 0L : Long.parseLong(new String(current, StandardCharsets.UTF_8));
                        return Long.toString(currentValue + 1).getBytes(StandardCharsets.UTF_8);
                    });
                    yield new Response(Status.OK, ResultType.INT64,
                            int64(Long.parseLong(new String(updated, StandardCharsets.UTF_8))),
                            request.requestId());
                }
                default -> new Response(Status.ERROR, ResultType.ERROR_MSG,
                        OpCode.INFO.name().getBytes(StandardCharsets.UTF_8),
                        request.requestId());
            };
            return CompletableFuture.completedFuture(response);
        }

        @Override
        public void close() {
        }

        private static String key(byte[] key) {
            return Base64.getEncoder().encodeToString(key);
        }
    }
}
