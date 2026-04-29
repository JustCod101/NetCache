package com.netcache.client;

import com.netcache.client.pool.NodeChannel;
import com.netcache.protocol.OpCode;
import com.netcache.protocol.ResultType;
import com.netcache.protocol.Status;
import com.netcache.protocol.command.Request;
import com.netcache.protocol.command.Response;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultNetCacheClientTest {
    @Test
    void supportsBasicSyncAndAsyncOperations() throws Exception {
        InMemoryBackend backend = new InMemoryBackend();
        try (NetCacheClient client = NetCacheClient.builder()
                .seeds("local:1")
                .channelFactory((seed, index) -> new InMemoryNodeChannel(backend))
                .build()) {
            byte[] key = bytes("alpha");

            client.set(key, bytes("1"));

            assertThat(client.get(key)).containsExactly(bytes("1"));
            assertThat(client.incr(key)).isEqualTo(2L);
            assertThat(client.expire(key, Duration.ofSeconds(1))).isTrue();
            assertThat(client.del(key)).isTrue();
            assertThat(client.get(key)).isNull();
            assertThat(client.setAsync(key, bytes("async"))).succeedsWithin(Duration.ofSeconds(1));
            assertThat(client.getAsync(key).get(1, TimeUnit.SECONDS)).containsExactly(bytes("async"));
        }
    }

    @Test
    void oneThousandThreadsCompleteFiveHundredThousandSetGetOperations() throws Exception {
        int threads = 1_000;
        int operationsPerThread = 500;
        InMemoryBackend backend = new InMemoryBackend();
        try (NetCacheClient client = NetCacheClient.builder()
                .seeds("local:1")
                .poolSizePerNode(8)
                .channelFactory((seed, index) -> new InMemoryNodeChannel(backend))
                .build()) {
            var executor = Executors.newFixedThreadPool(threads, runnable -> {
                Thread thread = new Thread(runnable);
                thread.setName("nc-client-test-" + thread.threadId());
                return thread;
            });
            CountDownLatch start = new CountDownLatch(1);
            java.util.List<java.util.concurrent.Future<?>> futures = new java.util.ArrayList<>(threads);
            for (int threadIndex = 0; threadIndex < threads; threadIndex++) {
                final int partition = threadIndex;
                futures.add(executor.submit(() -> {
                    start.await();
                    for (int i = 0; i < operationsPerThread; i++) {
                        byte[] key = bytes("k-" + partition + '-' + i);
                        byte[] value = bytes("v-" + i);
                        client.set(key, value);
                        byte[] found = client.get(key);
                        if (!Arrays.equals(value, found)) {
                            throw new AssertionError("value mismatch for partition " + partition + " op " + i);
                        }
                    }
                    return null;
                }));
            }

            start.countDown();
            for (java.util.concurrent.Future<?> future : futures) {
                future.get(60, TimeUnit.SECONDS);
            }
            executor.shutdown();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
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
        public java.util.concurrent.CompletableFuture<Response> send(Request request) {
            java.util.List<byte[]> args = request.args();
            Response response = switch (request.opCode()) {
                case GET -> {
                    byte[] value = backend.values.get(key(args.get(0)));
                    yield value == null
                            ? new Response(Status.NIL, ResultType.NULL, new byte[0], request.requestId())
                            : new Response(Status.OK, ResultType.BYTES, value, request.requestId());
                }
                case SET -> {
                    backend.values.put(key(args.get(0)), args.get(1).clone());
                    yield new Response(Status.OK, ResultType.NULL, new byte[0], request.requestId());
                }
                case DEL -> new Response(Status.OK, ResultType.INT64, int64(backend.values.remove(key(args.get(0))) == null ? 0L : 1L), request.requestId());
                case EXPIRE -> new Response(Status.OK, ResultType.INT64, int64(backend.values.containsKey(key(args.get(0))) ? 1L : 0L), request.requestId());
                case INCR -> {
                    byte[] updated = backend.values.compute(key(args.get(0)), (ignored, current) -> {
                        long currentValue = current == null ? 0L : Long.parseLong(new String(current, StandardCharsets.UTF_8));
                        return Long.toString(currentValue + 1).getBytes(StandardCharsets.UTF_8);
                    });
                    yield new Response(Status.OK, ResultType.INT64, int64(Long.parseLong(new String(updated, StandardCharsets.UTF_8))), request.requestId());
                }
                default -> new Response(Status.ERROR, ResultType.ERROR_MSG, bytes("unsupported"), request.requestId());
            };
            return java.util.concurrent.CompletableFuture.completedFuture(response);
        }

        @Override
        public void close() {
        }

        private static String key(byte[] key) {
            return java.util.Base64.getEncoder().encodeToString(key);
        }
    }
}
