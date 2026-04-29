package com.netcache.client;

import com.netcache.client.pool.ConnectionPool;
import com.netcache.client.pool.NodeChannelFactory;
import com.netcache.client.retry.RetryPolicy;
import com.netcache.client.routing.RequestRouter;
import com.netcache.client.routing.TopologyCache;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

public final class ClientBuilder {
    private final List<String> seeds = new ArrayList<>();
    private int poolSizePerNode = 8;
    private Duration connectTimeout = Duration.ofMillis(500);
    private Duration readTimeout = Duration.ofSeconds(2);
    private int maxRetries = 3;
    private NodeChannelFactory channelFactory;

    public ClientBuilder seeds(String... seeds) {
        this.seeds.clear();
        this.seeds.addAll(Arrays.asList(Objects.requireNonNull(seeds, "seeds")));
        return this;
    }

    public ClientBuilder poolSizePerNode(int poolSizePerNode) {
        if (poolSizePerNode <= 0) {
            throw new IllegalArgumentException("poolSizePerNode must be positive");
        }
        this.poolSizePerNode = poolSizePerNode;
        return this;
    }

    public ClientBuilder connectTimeout(Duration connectTimeout) {
        this.connectTimeout = Objects.requireNonNull(connectTimeout, "connectTimeout");
        return this;
    }

    public ClientBuilder readTimeout(Duration readTimeout) {
        this.readTimeout = Objects.requireNonNull(readTimeout, "readTimeout");
        return this;
    }

    public ClientBuilder maxRetries(int maxRetries) {
        if (maxRetries < 0) {
            throw new IllegalArgumentException("maxRetries must be non-negative");
        }
        this.maxRetries = maxRetries;
        return this;
    }

    public ClientBuilder channelFactory(NodeChannelFactory channelFactory) {
        this.channelFactory = Objects.requireNonNull(channelFactory, "channelFactory");
        return this;
    }

    public NetCacheClient build() {
        if (seeds.isEmpty()) {
            throw new IllegalStateException("at least one seed is required");
        }
        NodeChannelFactory factory = channelFactory == null
                ? NodeChannelFactory.tcp(connectTimeout, readTimeout)
                : channelFactory;
        TopologyCache topologyCache = new TopologyCache(seeds);
        ConnectionPool pool = new ConnectionPool(topologyCache, poolSizePerNode, factory);
        return new DefaultNetCacheClient(new RequestRouter(topologyCache, pool), new RetryPolicy(maxRetries));
    }
}
