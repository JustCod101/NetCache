package com.netcache.protocol;

import java.util.Arrays;

public enum Status {
    OK(0x00),
    ERROR(0x01),
    MOVED(0x02),
    ASK(0x03),
    NIL(0x04);

    private final byte code;

    Status(int code) {
        this.code = (byte) code;
    }

    public byte code() {
        return code;
    }

    public static Status fromCode(byte code) {
        return Arrays.stream(values())
                .filter(status -> status.code == code)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown status: 0x" + Integer.toHexString(Byte.toUnsignedInt(code))));
    }
}
