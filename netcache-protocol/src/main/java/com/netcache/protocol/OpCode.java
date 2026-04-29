package com.netcache.protocol;

import java.util.Arrays;

public enum OpCode {
    GET(0x10),
    SET(0x11),
    DEL(0x12),
    EXPIRE(0x13),
    TTL(0x14),
    EXISTS(0x15),
    INCR(0x16),
    DECR(0x17),
    PING(0x20),
    INFO(0x21),
    CLUSTER_NODES(0x30),
    SLAVEOF(0x40),
    PSYNC(0x41),
    SENTINEL_HELLO(0x50),
    SENTINEL_FAILOVER(0x51);

    private final byte code;

    OpCode(int code) {
        this.code = (byte) code;
    }

    public byte code() {
        return code;
    }

    public static OpCode fromCode(byte code) {
        return Arrays.stream(values())
                .filter(opCode -> opCode.code == code)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown op code: 0x" + Integer.toHexString(Byte.toUnsignedInt(code))));
    }
}
