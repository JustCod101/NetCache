package com.netcache.client.routing;

import com.netcache.cluster.ClusterTopology;
import com.netcache.cluster.NodeEndpoint;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public final class TopologyCache {
    private final List<String> seeds;
    private final AtomicInteger cursor = new AtomicInteger();
    private final AtomicReference<ClusterTopology> topology = new AtomicReference<>();

    public TopologyCache(List<String> seeds) {
        Objects.requireNonNull(seeds, "seeds");
        if (seeds.isEmpty()) {
            throw new IllegalArgumentException("seeds must not be empty");
        }
        this.seeds = List.copyOf(seeds);
    }

    public List<String> seeds() {
        return seeds;
    }

    public String route(byte[] key) {
        ClusterTopology current = topology.get();
        if (current != null) {
            return current.route(key).address();
        }
        if (seeds.size() == 1) {
            return seeds.get(0);
        }
        return seeds.get(Math.floorMod(java.util.Arrays.hashCode(key), seeds.size()));
    }

    public void updateTopology(ClusterTopology clusterTopology) {
        topology.set(Objects.requireNonNull(clusterTopology, "clusterTopology"));
    }

    public Optional<NodeEndpoint> routeEndpoint(byte[] key) {
        ClusterTopology current = topology.get();
        return current == null ? Optional.empty() : Optional.of(current.route(key));
    }

    public String nextSeed() {
        return seeds.get(Math.floorMod(cursor.getAndIncrement(), seeds.size()));
    }
}
