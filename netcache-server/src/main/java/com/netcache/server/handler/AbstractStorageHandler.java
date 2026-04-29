package com.netcache.server.handler;

import com.netcache.common.ByteKey;
import com.netcache.storage.StorageEngine;

import java.util.List;
import java.util.Objects;

abstract class AbstractStorageHandler implements CommandHandler {
    protected final StorageEngine storageEngine;

    AbstractStorageHandler(StorageEngine storageEngine) {
        this.storageEngine = Objects.requireNonNull(storageEngine, "storageEngine");
    }

    protected static ByteKey key(List<byte[]> args, int index) {
        return ByteKey.copyOf(arg(args, index));
    }

    protected static byte[] arg(List<byte[]> args, int index) {
        if (args.size() <= index) {
            throw new IllegalArgumentException("missing argument at index " + index);
        }
        return args.get(index);
    }

    protected static void requireArgCount(List<byte[]> args, int min, int max) {
        if (args.size() < min || args.size() > max) {
            throw new IllegalArgumentException("expected " + min + ".." + max + " arguments but got " + args.size());
        }
    }
}
