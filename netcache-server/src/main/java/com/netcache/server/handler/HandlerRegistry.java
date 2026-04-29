package com.netcache.server.handler;

import com.netcache.protocol.OpCode;
import com.netcache.storage.StorageEngine;

import java.util.EnumMap;
import java.util.Map;

public final class HandlerRegistry {
    private HandlerRegistry() {
    }

    public static Map<OpCode, CommandHandler> singleNode(StorageEngine storageEngine) {
        Map<OpCode, CommandHandler> handlers = new EnumMap<>(OpCode.class);
        handlers.put(OpCode.GET, new GetHandler(storageEngine));
        handlers.put(OpCode.SET, new SetHandler(storageEngine));
        handlers.put(OpCode.DEL, new DelHandler(storageEngine));
        handlers.put(OpCode.EXPIRE, new ExpireHandler(storageEngine));
        handlers.put(OpCode.TTL, new TtlHandler(storageEngine));
        handlers.put(OpCode.EXISTS, new ExistsHandler(storageEngine));
        handlers.put(OpCode.INCR, new IncrHandler(storageEngine));
        handlers.put(OpCode.DECR, new DecrHandler(storageEngine));
        handlers.put(OpCode.PING, new PingHandler());
        handlers.put(OpCode.INFO, new InfoHandler(storageEngine));
        return handlers;
    }
}
