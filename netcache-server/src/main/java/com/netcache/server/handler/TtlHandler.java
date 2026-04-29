package com.netcache.server.handler;

import com.netcache.protocol.command.Request;
import com.netcache.protocol.command.Response;
import com.netcache.storage.StorageEngine;

public final class TtlHandler extends AbstractStorageHandler {
    public TtlHandler(StorageEngine storageEngine) {
        super(storageEngine);
    }

    @Override
    public Response handle(Request request) {
        requireArgCount(request.args(), 1, 1);
        return Responses.int64(request.requestId(), storageEngine.ttl(key(request.args(), 0)));
    }
}
