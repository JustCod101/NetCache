package com.netcache.common.exception;

public final class ProtocolException extends NetCacheException {
    public ProtocolException(String message) {
        super("PROTOCOL_ERROR", message);
    }

    public ProtocolException(String message, Throwable cause) {
        super("PROTOCOL_ERROR", message, cause);
    }
}
