package com.netcache.client.pool;

import com.netcache.client.routing.TopologyCache;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public final class ConnectionPool implements AutoCloseable {
    private final ConcurrentHashMap<String, List<NodeChannel>> channelsBySeed = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicInteger> cursors = new ConcurrentHashMap<>();

    public ConnectionPool(TopologyCache topologyCache, int poolSizePerNode, NodeChannelFactory channelFactory) {
        Objects.requireNonNull(topologyCache, "topologyCache");
        Objects.requireNonNull(channelFactory, "channelFactory");
        if (poolSizePerNode <= 0) {
            throw new IllegalArgumentException("poolSizePerNode must be positive");
        }
        for (String seed : topologyCache.seeds()) {
            List<NodeChannel> channels = new ArrayList<>(poolSizePerNode);
            for (int i = 0; i < poolSizePerNode; i++) {
                channels.add(channelFactory.connect(seed, i));
            }
            channelsBySeed.put(seed, List.copyOf(channels));
            cursors.put(seed, new AtomicInteger());
        }
    }

    public NodeChannel channel(String seed) {
        List<NodeChannel> channels = channelsBySeed.get(seed);
        if (channels == null || channels.isEmpty()) {
            throw new IllegalStateException("no channels for seed " + seed);
        }
        int index = Math.floorMod(cursors.get(seed).getAndIncrement(), channels.size());
        return channels.get(index);
    }

    @Override
    public void close() {
        channelsBySeed.values().forEach(channels -> channels.forEach(NodeChannel::close));
    }
}
