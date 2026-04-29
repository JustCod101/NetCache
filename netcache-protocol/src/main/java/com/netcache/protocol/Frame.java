package com.netcache.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.util.ReferenceCountUtil;

import java.util.Objects;

public record Frame(int magic, byte version, byte type, long requestId, ByteBuf payload) implements AutoCloseable {
    public static final int MAGIC = 0xC0DECAFE;
    public static final byte VERSION = 0x01;
    public static final int HEADER_LENGTH = 18;
    public static final int MAX_PAYLOAD_LENGTH = 16 * 1024 * 1024;

    public static final byte TYPE_REQUEST = 0x01;
    public static final byte TYPE_RESPONSE = 0x02;
    public static final byte TYPE_REPLICATION = 0x03;
    public static final byte TYPE_SENTINEL_HEARTBEAT = 0x04;

    public Frame {
        Objects.requireNonNull(payload, "payload");
        if (magic != MAGIC) {
            throw new IllegalArgumentException("invalid magic: 0x" + Integer.toHexString(magic));
        }
        if (version != VERSION) {
            throw new IllegalArgumentException("unsupported version: " + version);
        }
        int readableBytes = payload.readableBytes();
        if (readableBytes > MAX_PAYLOAD_LENGTH) {
            throw new IllegalArgumentException("payload exceeds 16MB limit: " + readableBytes);
        }
    }

    public static Frame request(long requestId, ByteBuf payload) {
        return new Frame(MAGIC, VERSION, TYPE_REQUEST, requestId, payload);
    }

    public static Frame response(long requestId, ByteBuf payload) {
        return new Frame(MAGIC, VERSION, TYPE_RESPONSE, requestId, payload);
    }

    @Override
    public void close() {
        ReferenceCountUtil.release(payload);
    }
}
