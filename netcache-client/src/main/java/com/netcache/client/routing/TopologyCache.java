package com.netcache.client.routing;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

public final class TopologyCache {
    private final List<String> seeds;
    private final AtomicInteger cursor = new AtomicInteger();

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
        if (seeds.size() == 1) {
            return seeds.get(0);
        }
        return seeds.get(Math.floorMod(java.util.Arrays.hashCode(key), seeds.size()));
    }

    public String nextSeed() {
        return seeds.get(Math.floorMod(cursor.getAndIncrement(), seeds.size()));
    }
}
