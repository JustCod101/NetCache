package com.netcache.protocol.command;

import com.netcache.protocol.OpCode;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;

import java.util.List;
import java.util.Objects;

public record Request(OpCode opCode, List<byte[]> args, long requestId) {
    public Request {
        Objects.requireNonNull(opCode, "opCode");
        Objects.requireNonNull(args, "args");
        if (args.size() > 0xffff) {
            throw new IllegalArgumentException("argument count exceeds unsigned short: " + args.size());
        }
        args = List.copyOf(args.stream()
                .map(arg -> Objects.requireNonNull(arg, "arg").clone())
                .toList());
    }

    public ByteBuf encodePayload(ByteBufAllocator allocator) {
        Objects.requireNonNull(allocator, "allocator");
        int length = 1 + Short.BYTES + args.stream().mapToInt(arg -> Integer.BYTES + arg.length).sum();
        ByteBuf out = allocator.buffer(length, length);
        out.writeByte(opCode.code());
        out.writeShort(args.size());
        for (byte[] arg : args) {
            out.writeInt(arg.length);
            out.writeBytes(arg);
        }
        return out;
    }

    public static Request decodePayload(long requestId, ByteBuf payload) {
        Objects.requireNonNull(payload, "payload");
        OpCode opCode = OpCode.fromCode(payload.readByte());
        int argCount = payload.readUnsignedShort();
        List<byte[]> args = new java.util.ArrayList<>(argCount);
        for (int i = 0; i < argCount; i++) {
            int length = payload.readInt();
            if (length < 0 || payload.readableBytes() < length) {
                throw new IllegalArgumentException("invalid argument length: " + length);
            }
            byte[] arg = new byte[length];
            payload.readBytes(arg);
            args.add(arg);
        }
        return new Request(opCode, args, requestId);
    }

    @Override
    public List<byte[]> args() {
        return args.stream().map(byte[]::clone).toList();
    }
}
