package com.netcache.cluster.migration;

import com.netcache.cluster.HashRing;
import com.netcache.common.NodeId;

import java.util.List;
import java.util.Objects;

public final class MigrationPlanner {
    private final HashRing hashRing;

    public MigrationPlanner(HashRing hashRing) {
        this.hashRing = Objects.requireNonNull(hashRing, "hashRing");
    }

    public List<KeyMigration> addNode(NodeId nodeId) {
        return hashRing.addNode(nodeId);
    }

    public List<KeyMigration> removeNode(NodeId nodeId) {
        return hashRing.removeNode(nodeId);
    }
}
