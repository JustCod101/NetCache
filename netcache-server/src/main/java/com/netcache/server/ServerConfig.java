package com.netcache.server;

import java.util.Objects;

public record ServerConfig(String host, int port, int bossThreads, int workerThreads) {
    public ServerConfig {
        Objects.requireNonNull(host, "host");
        if (port <= 0 || port > 65535) {
            throw new IllegalArgumentException("port must be 1..65535");
        }
        if (bossThreads <= 0) {
            throw new IllegalArgumentException("bossThreads must be positive");
        }
        if (workerThreads < 0) {
            throw new IllegalArgumentException("workerThreads must be zero or positive");
        }
    }

    public static ServerConfig defaults() {
        return new ServerConfig("0.0.0.0", 7001, 1, 0);
    }

    public static ServerConfig fromSystemProperties() {
        ServerConfig defaults = defaults();
        return new ServerConfig(
                System.getProperty("netcache.host", defaults.host()),
                Integer.getInteger("netcache.port", defaults.port()),
                Integer.getInteger("netcache.bossThreads", defaults.bossThreads()),
                Integer.getInteger("netcache.workerThreads", defaults.workerThreads()));
    }

    public int effectiveWorkerThreads() {
        return workerThreads == 0 ? Runtime.getRuntime().availableProcessors() * 2 : workerThreads;
    }
}
