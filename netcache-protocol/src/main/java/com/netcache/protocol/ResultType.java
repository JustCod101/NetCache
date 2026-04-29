package com.netcache.protocol;

import java.util.Arrays;

public enum ResultType {
    NULL(0x00),
    STRING(0x01),
    INT64(0x02),
    BYTES(0x03),
    NODE_LIST(0x04),
    ERROR_MSG(0x05);

    private final byte code;

    ResultType(int code) {
        this.code = (byte) code;
    }

    public byte code() {
        return code;
    }

    public static ResultType fromCode(byte code) {
        return Arrays.stream(values())
                .filter(resultType -> resultType.code == code)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown result type: 0x" + Integer.toHexString(Byte.toUnsignedInt(code))));
    }
}
