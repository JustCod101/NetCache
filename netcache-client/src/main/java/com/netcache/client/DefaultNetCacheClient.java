package com.netcache.client;

import com.netcache.protocol.OpCode;
import com.netcache.protocol.ResultType;
import com.netcache.protocol.Status;
import com.netcache.protocol.command.Request;
import com.netcache.protocol.command.Response;
import com.netcache.client.retry.RetryPolicy;
import com.netcache.client.routing.RequestRouter;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public final class DefaultNetCacheClient implements NetCacheClient {
    private final RequestRouter requestRouter;
    private final RetryPolicy retryPolicy;
    private final AtomicLong requestIds = new AtomicLong(1);

    public DefaultNetCacheClient(RequestRouter requestRouter, RetryPolicy retryPolicy) {
        this.requestRouter = Objects.requireNonNull(requestRouter, "requestRouter");
        this.retryPolicy = Objects.requireNonNull(retryPolicy, "retryPolicy");
    }

    @Override
    public byte[] get(byte[] key) {
        return join(getAsync(key));
    }

    @Override
    public void set(byte[] key, byte[] value) {
        join(setAsync(key, value));
    }

    @Override
    public void set(byte[] key, byte[] value, Duration ttl) {
        join(setAsync(key, value, ttl));
    }

    @Override
    public long incr(byte[] key) {
        return join(incrAsync(key));
    }

    @Override
    public boolean del(byte[] key) {
        return join(delAsync(key));
    }

    @Override
    public boolean expire(byte[] key, Duration ttl) {
        return join(expireAsync(key, ttl));
    }

    @Override
    public CompletableFuture<byte[]> getAsync(byte[] key) {
        return send(OpCode.GET, List.of(copy(key))).thenApply(response -> response.status() == Status.NIL ? null : bytes(response));
    }

    @Override
    public CompletableFuture<Void> setAsync(byte[] key, byte[] value) {
        return setAsync(key, value, Duration.ZERO);
    }

    @Override
    public CompletableFuture<Void> setAsync(byte[] key, byte[] value, Duration ttl) {
        List<byte[]> args = new ArrayList<>();
        args.add(copy(key));
        args.add(copy(value));
        if (!ttl.isZero() && !ttl.isNegative()) {
            args.add(Long.toString(ttl.toMillis()).getBytes(StandardCharsets.UTF_8));
        }
        return send(OpCode.SET, args).thenApply(DefaultNetCacheClient::voidResult);
    }

    @Override
    public CompletableFuture<Long> incrAsync(byte[] key) {
        return send(OpCode.INCR, List.of(copy(key))).thenApply(DefaultNetCacheClient::int64);
    }

    @Override
    public CompletableFuture<Boolean> delAsync(byte[] key) {
        return send(OpCode.DEL, List.of(copy(key))).thenApply(response -> int64(response) == 1L);
    }

    @Override
    public CompletableFuture<Boolean> expireAsync(byte[] key, Duration ttl) {
        Objects.requireNonNull(ttl, "ttl");
        return send(OpCode.EXPIRE, List.of(copy(key), Long.toString(ttl.toMillis()).getBytes(StandardCharsets.UTF_8)))
                .thenApply(response -> int64(response) == 1L);
    }

    @Override
    public void close() {
        requestRouter.close();
    }

    private CompletableFuture<Response> send(OpCode opCode, List<byte[]> args) {
        Request request = new Request(opCode, args, requestIds.getAndIncrement());
        return retryPolicy.execute(() -> requestRouter.route(request));
    }

    private static Void voidResult(Response response) {
        ensureOk(response);
        return null;
    }

    private static byte[] bytes(Response response) {
        ensureOk(response);
        if (response.type() != ResultType.BYTES && response.type() != ResultType.STRING) {
            throw new IllegalStateException("expected byte response but got " + response.type());
        }
        return response.body();
    }

    private static long int64(Response response) {
        ensureOk(response);
        if (response.type() != ResultType.INT64) {
            throw new IllegalStateException("expected int64 response but got " + response.type());
        }
        return ByteBuffer.wrap(response.body()).getLong();
    }

    private static void ensureOk(Response response) {
        if (response.status() != Status.OK) {
            throw new IllegalStateException(new String(response.body(), StandardCharsets.UTF_8));
        }
    }

    private static byte[] copy(byte[] value) {
        return Objects.requireNonNull(value, "value").clone();
    }

    private static <T> T join(CompletableFuture<T> future) {
        try {
            return future.get(30, TimeUnit.SECONDS);
        } catch (Exception ex) {
            throw new CompletionException(ex);
        }
    }
}
