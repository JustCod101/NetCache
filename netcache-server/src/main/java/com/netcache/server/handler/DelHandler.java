package com.netcache.server.handler;

import com.netcache.protocol.command.Request;
import com.netcache.protocol.command.Response;
import com.netcache.storage.StorageEngine;

public final class DelHandler extends AbstractStorageHandler {
    public DelHandler(StorageEngine storageEngine) {
        super(storageEngine);
    }

    @Override
    public Response handle(Request request) {
        requireArgCount(request.args(), 1, 1);
        return Responses.int64(request.requestId(), storageEngine.del(key(request.args(), 0)) ? 1L : 0L);
    }
}
