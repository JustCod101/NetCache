package com.netcache.cluster;

import com.netcache.common.NodeId;
import com.netcache.common.NodeRole;

import java.util.Collection;
import java.util.Comparator;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public final class ClusterTopology {
    private final AtomicLong epoch = new AtomicLong();
    private final ConcurrentHashMap<NodeId, NodeEndpoint> nodes = new ConcurrentHashMap<>();
    private final HashRing hashRing;

    public ClusterTopology() {
        this(new HashRing());
    }

    public ClusterTopology(HashRing hashRing) {
        this.hashRing = Objects.requireNonNull(hashRing, "hashRing");
    }

    public boolean apply(long newEpoch, Collection<NodeEndpoint> newNodes) {
        Objects.requireNonNull(newNodes, "newNodes");
        if (newEpoch <= epoch.get()) {
            return false;
        }
        nodes.clear();
        HashRing replacement = new HashRing();
        for (NodeEndpoint node : newNodes) {
            nodes.put(node.nodeId(), node);
            if (node.role() == NodeRole.MASTER) {
                replacement.addNode(node.nodeId());
            }
        }
        synchronized (hashRing) {
            for (NodeId nodeId : hashRing.nodes()) {
                hashRing.removeNode(nodeId);
            }
            for (NodeId nodeId : replacement.nodes()) {
                hashRing.addNode(nodeId);
            }
        }
        epoch.set(newEpoch);
        return true;
    }

    public long epoch() {
        return epoch.get();
    }

    public Optional<NodeEndpoint> node(NodeId nodeId) {
        return Optional.ofNullable(nodes.get(nodeId));
    }

    public NodeEndpoint route(byte[] key) {
        NodeId nodeId = hashRing.routeOf(key);
        NodeEndpoint endpoint = nodes.get(nodeId);
        if (endpoint == null) {
            throw new IllegalStateException("routed node is missing from topology: " + nodeId);
        }
        return endpoint;
    }

    public Collection<NodeEndpoint> nodes() {
        return nodes.values().stream()
                .sorted(Comparator.comparing(NodeEndpoint::address))
                .toList();
    }

    public Map<NodeId, NodeEndpoint> nodeMap() {
        return Map.copyOf(nodes);
    }
}
