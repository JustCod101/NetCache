package com.netcache.server;

import com.netcache.server.lifecycle.NodeLifecycle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class NetCacheServer {
    private static final Logger LOG = LoggerFactory.getLogger(NetCacheServer.class);

    private NetCacheServer() {
    }

    public static void main(String[] args) throws InterruptedException {
        ServerConfig config = ServerConfig.defaults();
        NodeLifecycle lifecycle = new NodeLifecycle(config);
        Runtime.getRuntime().addShutdownHook(new Thread(lifecycle::stop, "nc-server-shutdown"));
        lifecycle.start();
        LOG.info("NetCache server started on {}:{}", config.host(), config.port());
        lifecycle.closeFuture().sync();
    }
}
