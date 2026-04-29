package com.netcache.server.handler;

import com.netcache.protocol.command.Request;
import com.netcache.protocol.command.Response;
import com.netcache.storage.StorageEngine;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

public final class SetHandler extends AbstractStorageHandler {
    public SetHandler(StorageEngine storageEngine) {
        super(storageEngine);
    }

    @Override
    public Response handle(Request request) {
        requireArgCount(request.args(), 2, 3);
        Duration ttl = request.args().size() == 3
                ? Duration.ofMillis(Long.parseLong(new String(arg(request.args(), 2), StandardCharsets.UTF_8)))
                : Duration.ZERO;
        storageEngine.set(key(request.args(), 0), arg(request.args(), 1), ttl);
        return Responses.okNull(request.requestId());
    }
}
