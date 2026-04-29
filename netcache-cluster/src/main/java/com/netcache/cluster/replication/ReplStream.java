package com.netcache.cluster.replication;

import com.netcache.protocol.OpCode;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;

import java.util.Objects;

public record ReplStream(long offset, OpCode opCode, byte[] key, byte[] value) {
    public ReplStream {
        Objects.requireNonNull(opCode, "opCode");
        key = Objects.requireNonNull(key, "key").clone();
        value = Objects.requireNonNull(value, "value").clone();
    }

    @Override
    public byte[] key() {
        return key.clone();
    }

    @Override
    public byte[] value() {
        return value.clone();
    }

    public ByteBuf encode(ByteBufAllocator allocator) {
        ByteBuf out = allocator.buffer(Long.BYTES + 1 + Integer.BYTES + key.length + Integer.BYTES + value.length);
        out.writeLong(offset);
        out.writeByte(opCode.code());
        out.writeInt(key.length);
        out.writeBytes(key);
        out.writeInt(value.length);
        out.writeBytes(value);
        return out;
    }

    public static ReplStream decode(ByteBuf in) {
        long offset = in.readLong();
        OpCode opCode = OpCode.fromCode(in.readByte());
        int keyLength = in.readInt();
        byte[] key = new byte[keyLength];
        in.readBytes(key);
        int valueLength = in.readInt();
        byte[] value = new byte[valueLength];
        in.readBytes(value);
        return new ReplStream(offset, opCode, key, value);
    }
}
