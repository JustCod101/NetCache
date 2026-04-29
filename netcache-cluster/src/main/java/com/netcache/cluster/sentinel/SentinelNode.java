package com.netcache.cluster.sentinel;

import com.netcache.cluster.ClusterTopology;
import com.netcache.common.NodeId;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public final class SentinelNode {
    private final NodeId sentinelId;
    private final Set<NodeId> peerSentinels;
    private final int quorum;
    private final ClusterTopology topology;
    private final HealthChecker healthChecker;
    private final QuorumDecision quorumDecision;
    private final FailoverCoordinator failoverCoordinator;

    public SentinelNode(NodeId sentinelId,
                        Collection<NodeId> peerSentinels,
                        int quorum,
                        long pingIntervalMs,
                        long sdownAfterMs,
                        long failoverTimeoutMs,
                        ClusterTopology topology) {
        this(sentinelId,
                peerSentinels,
                quorum,
                topology,
                new HealthChecker(pingIntervalMs, sdownAfterMs),
                new QuorumDecision(),
                new FailoverCoordinator(new RaftLite(), quorum, failoverTimeoutMs));
    }

    public SentinelNode(NodeId sentinelId,
                        Collection<NodeId> peerSentinels,
                        int quorum,
                        ClusterTopology topology,
                        HealthChecker healthChecker,
                        QuorumDecision quorumDecision,
                        FailoverCoordinator failoverCoordinator) {
        this.sentinelId = Objects.requireNonNull(sentinelId, "sentinelId");
        this.peerSentinels = Set.copyOf(Objects.requireNonNull(peerSentinels, "peerSentinels"));
        if (quorum <= 0) {
            throw new IllegalArgumentException("quorum must be > 0");
        }
        this.quorum = quorum;
        this.topology = Objects.requireNonNull(topology, "topology");
        this.healthChecker = Objects.requireNonNull(healthChecker, "healthChecker");
        this.quorumDecision = Objects.requireNonNull(quorumDecision, "quorumDecision");
        this.failoverCoordinator = Objects.requireNonNull(failoverCoordinator, "failoverCoordinator");
    }

    public NodeId sentinelId() {
        return sentinelId;
    }

    public ClusterTopology topology() {
        return topology;
    }

    public void recordPing(NodeId nodeId, boolean healthy, long nowMs) {
        healthChecker.recordPing(nodeId, healthy, nowMs);
    }

    public boolean isSubjectivelyDown(NodeId nodeId, long nowMs) {
        return healthChecker.isSubjectivelyDown(nodeId, nowMs);
    }

    public void observePeerSdown(NodeId targetNodeId, NodeId reportingSentinelId) {
        quorumDecision.recordSdownVote(targetNodeId, reportingSentinelId);
    }

    public Optional<FailoverCoordinator.FailoverResult> tryFailover(NodeId failedMasterId,
                                                                    Map<NodeId, Long> replicationOffsets,
                                                                    Map<NodeId, Integer> priorities,
                                                                    long nowMs) {
        Objects.requireNonNull(failedMasterId, "failedMasterId");
        if (!isSubjectivelyDown(failedMasterId, nowMs)) {
            return Optional.empty();
        }

        quorumDecision.recordSdownVote(failedMasterId, sentinelId);
        Set<NodeId> participatingSentinels = new LinkedHashSet<>(peerSentinels);
        participatingSentinels.add(sentinelId);

        Optional<FailoverCoordinator.FailoverResult> result = failoverCoordinator.failover(
                topology,
                failedMasterId,
                quorumDecision.voters(failedMasterId),
                participatingSentinels,
                replicationOffsets,
                priorities,
                nowMs,
                nowMs);

        result.ifPresent(failover -> {
            topology.apply(failover.epoch(), failover.nodes());
            quorumDecision.clear(failedMasterId);
        });
        return result;
    }
}
