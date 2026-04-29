package com.netcache.cluster.sentinel;

import com.netcache.cluster.ClusterTopology;
import com.netcache.cluster.NodeEndpoint;
import com.netcache.common.NodeId;
import com.netcache.common.NodeRole;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public final class FailoverCoordinator {
    private final RaftLite raftLite;
    private final int quorum;
    private final long failoverTimeoutMs;

    public FailoverCoordinator(RaftLite raftLite, int quorum, long failoverTimeoutMs) {
        this.raftLite = Objects.requireNonNull(raftLite, "raftLite");
        if (quorum <= 0) {
            throw new IllegalArgumentException("quorum must be > 0");
        }
        if (failoverTimeoutMs <= 0) {
            throw new IllegalArgumentException("failoverTimeoutMs must be > 0");
        }
        this.quorum = quorum;
        this.failoverTimeoutMs = failoverTimeoutMs;
    }

    public Optional<FailoverResult> failover(ClusterTopology topology,
                                             NodeId failedMasterId,
                                             Set<NodeId> objectiveDownVotes,
                                             Collection<NodeId> participatingSentinels,
                                             Map<NodeId, Long> replicationOffsets,
                                             Map<NodeId, Integer> priorities,
                                             long failoverStartedAtMs,
                                             long nowMs) {
        Objects.requireNonNull(topology, "topology");
        Objects.requireNonNull(failedMasterId, "failedMasterId");
        Objects.requireNonNull(objectiveDownVotes, "objectiveDownVotes");
        Objects.requireNonNull(participatingSentinels, "participatingSentinels");
        Objects.requireNonNull(replicationOffsets, "replicationOffsets");
        Objects.requireNonNull(priorities, "priorities");

        if (objectiveDownVotes.size() < quorum || nowMs - failoverStartedAtMs > failoverTimeoutMs) {
            return Optional.empty();
        }

        Optional<RaftLite.ElectionResult> election = raftLite.electLeader(participatingSentinels, quorum);
        if (election.isEmpty()) {
            return Optional.empty();
        }

        Optional<NodeEndpoint> candidate = topology.nodes().stream()
                .filter(node -> node.role() == NodeRole.SLAVE && failedMasterId.equals(node.masterId()))
                .max(Comparator
                        .comparingLong((NodeEndpoint node) -> replicationOffsets.getOrDefault(node.nodeId(), 0L))
                        .thenComparingInt(node -> priorities.getOrDefault(node.nodeId(), 0))
                        .thenComparing(NodeEndpoint::address));
        if (candidate.isEmpty()) {
            return Optional.empty();
        }

        NodeEndpoint promoted = candidate.get();
        List<NodeEndpoint> updatedNodes = topology.nodes().stream()
                .map(node -> rewriteNode(node, failedMasterId, promoted.nodeId()))
                .toList();

        long newEpoch = topology.epoch() + 1;
        return Optional.of(new FailoverResult(
                election.get().term(),
                election.get().leaderId(),
                failedMasterId,
                promoted.nodeId(),
                newEpoch,
                updatedNodes));
    }

    private static NodeEndpoint rewriteNode(NodeEndpoint node, NodeId failedMasterId, NodeId promotedNodeId) {
        if (node.nodeId().equals(promotedNodeId)) {
            return new NodeEndpoint(node.nodeId(), node.host(), node.port(), NodeRole.MASTER, null);
        }
        if (node.nodeId().equals(failedMasterId)
                || (node.role() == NodeRole.SLAVE && failedMasterId.equals(node.masterId()))) {
            return new NodeEndpoint(node.nodeId(), node.host(), node.port(), NodeRole.SLAVE, promotedNodeId);
        }
        return node;
    }

    public record FailoverResult(long term,
                                 NodeId leaderId,
                                 NodeId failedMasterId,
                                 NodeId promotedMasterId,
                                 long epoch,
                                 List<NodeEndpoint> nodes) {
        public FailoverResult {
            Objects.requireNonNull(leaderId, "leaderId");
            Objects.requireNonNull(failedMasterId, "failedMasterId");
            Objects.requireNonNull(promotedMasterId, "promotedMasterId");
            Objects.requireNonNull(nodes, "nodes");
        }
    }
}
