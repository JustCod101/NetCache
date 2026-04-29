package com.netcache.server.lifecycle;

import com.netcache.server.ServerConfig;
import com.netcache.server.handler.HandlerRegistry;
import com.netcache.server.netty.CommandDispatcher;
import com.netcache.server.netty.ServerBootstrapBuilder;
import com.netcache.storage.StorageEngine;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;

import java.util.Objects;

public final class NodeLifecycle {
    private final ServerConfig config;
    private final StorageEngine storageEngine;
    private ServerBootstrapBuilder.BuiltServerBootstrap built;
    private Channel channel;

    public NodeLifecycle(ServerConfig config) {
        this(config, new StorageEngine());
    }

    public NodeLifecycle(ServerConfig config, StorageEngine storageEngine) {
        this.config = Objects.requireNonNull(config, "config");
        this.storageEngine = Objects.requireNonNull(storageEngine, "storageEngine");
    }

    public void start() throws InterruptedException {
        CommandDispatcher dispatcher = new CommandDispatcher(HandlerRegistry.singleNode(storageEngine));
        built = new ServerBootstrapBuilder(config, dispatcher).build();
        channel = built.bootstrap().bind(config.host(), config.port()).sync().channel();
    }

    public ChannelFuture closeFuture() {
        if (channel == null) {
            throw new IllegalStateException("server not started");
        }
        return channel.closeFuture();
    }

    public void stop() {
        if (channel != null) {
            channel.close();
        }
        storageEngine.close();
        if (built != null) {
            built.shutdownGracefully();
        }
    }
}
