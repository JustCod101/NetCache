package com.netcache.server.handler;

import com.netcache.protocol.command.Request;
import com.netcache.protocol.command.Response;
import com.netcache.storage.StorageEngine;

public final class GetHandler extends AbstractStorageHandler {
    public GetHandler(StorageEngine storageEngine) {
        super(storageEngine);
    }

    @Override
    public Response handle(Request request) {
        requireArgCount(request.args(), 1, 1);
        return storageEngine.get(key(request.args(), 0))
                .map(value -> Responses.bytes(request.requestId(), value))
                .orElseGet(() -> Responses.nil(request.requestId()));
    }
}
