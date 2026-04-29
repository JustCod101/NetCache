package com.netcache.cluster;

import com.netcache.common.NodeId;
import com.netcache.common.util.HashUtil;
import com.netcache.cluster.migration.KeyMigration;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.TreeMap;

public final class HashRing {
    public static final int DEFAULT_VIRTUAL_NODES = 160;

    private final NavigableMap<Long, VirtualNode> ring = new TreeMap<>();
    private final Map<NodeId, List<VirtualNode>> nodeToVnodes = new HashMap<>();
    private final int virtualPerNode;

    public HashRing() {
        this(DEFAULT_VIRTUAL_NODES);
    }

    public HashRing(int virtualPerNode) {
        if (virtualPerNode <= 0) {
            throw new IllegalArgumentException("virtualPerNode must be positive");
        }
        this.virtualPerNode = virtualPerNode;
    }

    public synchronized NodeId routeOf(byte[] key) {
        Objects.requireNonNull(key, "key");
        if (ring.isEmpty()) {
            throw new IllegalStateException("hash ring has no nodes");
        }
        long hash = HashUtil.hash64(key);
        Map.Entry<Long, VirtualNode> entry = ring.ceilingEntry(hash);
        if (entry == null) {
            entry = ring.firstEntry();
        }
        return entry.getValue().nodeId();
    }

    public synchronized List<KeyMigration> addNode(NodeId nodeId) {
        Objects.requireNonNull(nodeId, "nodeId");
        if (nodeToVnodes.containsKey(nodeId)) {
            return List.of();
        }
        List<KeyMigration> migrations = new ArrayList<>();
        for (int i = 0; i < virtualPerNode; i++) {
            long hash = vnodeHash(nodeId, i);
            NodeId source = ownerAt(hash);
            VirtualNode vnode = new VirtualNode(nodeId, i, hash);
            ring.put(hash, vnode);
            nodeToVnodes.computeIfAbsent(nodeId, ignored -> new ArrayList<>()).add(vnode);
            if (source != null && !source.equals(nodeId)) {
                long startExclusive = previousHash(hash);
                migrations.add(new KeyMigration(source, nodeId, startExclusive, hash));
            }
        }
        return List.copyOf(migrations);
    }

    public synchronized List<KeyMigration> removeNode(NodeId nodeId) {
        Objects.requireNonNull(nodeId, "nodeId");
        List<VirtualNode> removed = nodeToVnodes.remove(nodeId);
        if (removed == null || removed.isEmpty()) {
            return List.of();
        }
        List<KeyMigration> migrations = new ArrayList<>();
        for (VirtualNode vnode : removed) {
            ring.remove(vnode.hash());
            NodeId target = ownerAt(vnode.hash());
            if (target != null) {
                migrations.add(new KeyMigration(nodeId, target, previousHash(vnode.hash()), vnode.hash()));
            }
        }
        return List.copyOf(migrations);
    }

    public synchronized boolean contains(NodeId nodeId) {
        return nodeToVnodes.containsKey(nodeId);
    }

    public synchronized List<NodeId> nodes() {
        return List.copyOf(nodeToVnodes.keySet());
    }

    public synchronized int virtualNodeCount() {
        return ring.size();
    }

    private NodeId ownerAt(long hash) {
        if (ring.isEmpty()) {
            return null;
        }
        Map.Entry<Long, VirtualNode> entry = ring.ceilingEntry(hash);
        if (entry == null) {
            entry = ring.firstEntry();
        }
        return entry.getValue().nodeId();
    }

    private long previousHash(long hash) {
        if (ring.isEmpty()) {
            return hash;
        }
        Long previous = ring.lowerKey(hash);
        return previous == null ? ring.lastKey() : previous;
    }

    private static long vnodeHash(NodeId nodeId, int index) {
        ByteBuffer buffer = ByteBuffer.allocate(Long.BYTES * 2 + Integer.BYTES);
        buffer.putLong(nodeId.id().getMostSignificantBits());
        buffer.putLong(nodeId.id().getLeastSignificantBits());
        buffer.putInt(index);
        return HashUtil.hash64(buffer.array());
    }
}
