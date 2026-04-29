package com.netcache.common.exception;

public class NetCacheException extends RuntimeException {
    private final String code;

    public NetCacheException(String code, String message) {
        super(message);
        this.code = requireCode(code);
    }

    public NetCacheException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = requireCode(code);
    }

    public String code() {
        return code;
    }

    private static String requireCode(String code) {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("code must not be blank");
        }
        return code;
    }
}
