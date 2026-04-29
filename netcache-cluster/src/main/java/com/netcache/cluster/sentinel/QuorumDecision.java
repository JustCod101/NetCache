package com.netcache.cluster.sentinel;

import com.netcache.common.NodeId;

import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class QuorumDecision {
    private final ConcurrentHashMap<NodeId, Set<NodeId>> votes = new ConcurrentHashMap<>();

    public void recordSdownVote(NodeId targetNodeId, NodeId sentinelId) {
        Objects.requireNonNull(targetNodeId, "targetNodeId");
        Objects.requireNonNull(sentinelId, "sentinelId");
        votes.computeIfAbsent(targetNodeId, ignored -> ConcurrentHashMap.newKeySet()).add(sentinelId);
    }

    public boolean reachesObjectiveDown(NodeId targetNodeId, int quorum) {
        if (quorum <= 0) {
            throw new IllegalArgumentException("quorum must be > 0");
        }
        return voters(targetNodeId).size() >= quorum;
    }

    public Set<NodeId> voters(NodeId targetNodeId) {
        Objects.requireNonNull(targetNodeId, "targetNodeId");
        return Collections.unmodifiableSet(votes.getOrDefault(targetNodeId, Set.of()));
    }

    public void clear(NodeId targetNodeId) {
        Objects.requireNonNull(targetNodeId, "targetNodeId");
        votes.remove(targetNodeId);
    }
}
