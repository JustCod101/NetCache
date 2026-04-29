package com.netcache.server.handler;

import com.netcache.protocol.command.Request;
import com.netcache.protocol.command.Response;
import com.netcache.storage.StorageEngine;

public final class ExistsHandler extends AbstractStorageHandler {
    public ExistsHandler(StorageEngine storageEngine) {
        super(storageEngine);
    }

    @Override
    public Response handle(Request request) {
        requireArgCount(request.args(), 1, 1);
        return Responses.int64(request.requestId(), storageEngine.exists(key(request.args(), 0)) ? 1L : 0L);
    }
}
