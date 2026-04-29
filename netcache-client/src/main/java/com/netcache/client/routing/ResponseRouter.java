package com.netcache.client.routing;

import com.netcache.protocol.Frame;
import com.netcache.protocol.command.Response;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public final class ResponseRouter extends SimpleChannelInboundHandler<Frame> {
    private final ConcurrentHashMap<Long, CompletableFuture<Response>> pending = new ConcurrentHashMap<>();
    private final Duration readTimeout;

    public ResponseRouter(Duration readTimeout) {
        this.readTimeout = Objects.requireNonNull(readTimeout, "readTimeout");
    }

    public CompletableFuture<Response> register(long requestId) {
        CompletableFuture<Response> future = new CompletableFuture<>();
        pending.put(requestId, future);
        future.orTimeout(readTimeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)
                .whenComplete((ignored, error) -> {
                    if (error != null) {
                        pending.remove(requestId);
                    }
                });
        return future;
    }

    public void fail(long requestId, Throwable cause) {
        CompletableFuture<Response> future = pending.remove(requestId);
        if (future != null) {
            future.completeExceptionally(cause);
        }
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, Frame frame) {
        try {
            CompletableFuture<Response> future = pending.remove(frame.requestId());
            if (future != null) {
                future.complete(Response.decodePayload(frame.requestId(), frame.payload().slice()));
            }
        } finally {
            frame.close();
        }
    }
}
