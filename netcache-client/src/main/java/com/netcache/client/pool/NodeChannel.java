package com.netcache.client.pool;

import com.netcache.protocol.command.Request;
import com.netcache.protocol.command.Response;

import java.util.concurrent.CompletableFuture;

public interface NodeChannel extends AutoCloseable {
    CompletableFuture<Response> send(Request request);

    @Override
    void close();
}
