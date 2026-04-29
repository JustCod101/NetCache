package com.netcache.client.routing;

import com.netcache.cluster.ClusterTopology;

import java.util.Objects;

public final class MovedHandler {
    private final TopologyCache topologyCache;

    public MovedHandler(TopologyCache topologyCache) {
        this.topologyCache = Objects.requireNonNull(topologyCache, "topologyCache");
    }

    public void refresh(ClusterTopology topology) {
        topologyCache.updateTopology(topology);
    }
}
