package com.netcache.server.handler;

import com.netcache.protocol.ResultType;
import com.netcache.protocol.Status;
import com.netcache.protocol.command.Response;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

final class Responses {
    private Responses() {
    }

    static Response okNull(long requestId) {
        return new Response(Status.OK, ResultType.NULL, new byte[0], requestId);
    }

    static Response nil(long requestId) {
        return new Response(Status.NIL, ResultType.NULL, new byte[0], requestId);
    }

    static Response bytes(long requestId, byte[] body) {
        return new Response(Status.OK, ResultType.BYTES, body, requestId);
    }

    static Response string(long requestId, String value) {
        return new Response(Status.OK, ResultType.STRING, value.getBytes(StandardCharsets.UTF_8), requestId);
    }

    static Response int64(long requestId, long value) {
        return new Response(Status.OK, ResultType.INT64, ByteBuffer.allocate(Long.BYTES).putLong(value).array(), requestId);
    }
}
