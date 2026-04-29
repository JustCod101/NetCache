package com.netcache.protocol.command;

import com.netcache.protocol.ResultType;
import com.netcache.protocol.Status;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;

import java.util.Objects;

public record Response(Status status, ResultType type, byte[] body, long requestId) {
    public Response {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(type, "type");
        body = Objects.requireNonNull(body, "body").clone();
    }

    public ByteBuf encodePayload(ByteBufAllocator allocator) {
        Objects.requireNonNull(allocator, "allocator");
        ByteBuf out = allocator.buffer(2 + body.length, 2 + body.length);
        out.writeByte(status.code());
        out.writeByte(type.code());
        out.writeBytes(body);
        return out;
    }

    public static Response decodePayload(long requestId, ByteBuf payload) {
        Objects.requireNonNull(payload, "payload");
        Status status = Status.fromCode(payload.readByte());
        ResultType type = ResultType.fromCode(payload.readByte());
        byte[] body = new byte[payload.readableBytes()];
        payload.readBytes(body);
        return new Response(status, type, body, requestId);
    }

    @Override
    public byte[] body() {
        return body.clone();
    }
}
