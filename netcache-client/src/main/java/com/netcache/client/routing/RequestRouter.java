package com.netcache.client.routing;

import com.netcache.client.pool.ConnectionPool;
import com.netcache.protocol.command.Request;
import com.netcache.protocol.command.Response;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;

public final class RequestRouter implements AutoCloseable {
    private final TopologyCache topologyCache;
    private final ConnectionPool connectionPool;

    public RequestRouter(TopologyCache topologyCache, ConnectionPool connectionPool) {
        this.topologyCache = Objects.requireNonNull(topologyCache, "topologyCache");
        this.connectionPool = Objects.requireNonNull(connectionPool, "connectionPool");
    }

    public CompletableFuture<Response> route(Request request) {
        String seed = request.args().isEmpty() ? topologyCache.nextSeed() : topologyCache.route(request.args().get(0));
        return connectionPool.channel(seed).send(request);
    }

    @Override
    public void close() {
        connectionPool.close();
    }
}
