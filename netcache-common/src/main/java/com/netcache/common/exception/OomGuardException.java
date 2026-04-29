package com.netcache.common.exception;

public final class OomGuardException extends StorageException {
    public OomGuardException(String message) {
        super("OOM_GUARD", message);
    }
}
