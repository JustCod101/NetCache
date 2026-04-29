package com.netcache.server.handler;

import com.netcache.protocol.command.Request;
import com.netcache.protocol.command.Response;
import com.netcache.storage.StorageEngine;

import java.util.Objects;

public final class InfoHandler implements CommandHandler {
    private final StorageEngine storageEngine;

    public InfoHandler(StorageEngine storageEngine) {
        this.storageEngine = Objects.requireNonNull(storageEngine, "storageEngine");
    }

    @Override
    public Response handle(Request request) {
        return Responses.string(request.requestId(), "role:master\nkeys:" + storageEngine.size());
    }
}
