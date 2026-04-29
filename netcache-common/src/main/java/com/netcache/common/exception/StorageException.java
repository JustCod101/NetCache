package com.netcache.common.exception;

public class StorageException extends NetCacheException {
    public StorageException(String code, String message) {
        super(code, message);
    }

    public StorageException(String code, String message, Throwable cause) {
        super(code, message, cause);
    }
}
