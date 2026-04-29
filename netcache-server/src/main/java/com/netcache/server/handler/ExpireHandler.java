package com.netcache.server.handler;

import com.netcache.protocol.command.Request;
import com.netcache.protocol.command.Response;
import com.netcache.storage.StorageEngine;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

public final class ExpireHandler extends AbstractStorageHandler {
    public ExpireHandler(StorageEngine storageEngine) {
        super(storageEngine);
    }

    @Override
    public Response handle(Request request) {
        requireArgCount(request.args(), 2, 2);
        long ttlMs = Long.parseLong(new String(arg(request.args(), 1), StandardCharsets.UTF_8));
        return Responses.int64(request.requestId(), storageEngine.expire(key(request.args(), 0), Duration.ofMillis(ttlMs)) ? 1L : 0L);
    }
}
