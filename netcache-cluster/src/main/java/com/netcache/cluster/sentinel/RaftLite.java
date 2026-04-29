package com.netcache.cluster.sentinel;

import com.netcache.common.NodeId;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

public final class RaftLite {
    private final AtomicLong currentTerm = new AtomicLong();

    public Optional<ElectionResult> electLeader(Collection<NodeId> participatingSentinels, int quorum) {
        Objects.requireNonNull(participatingSentinels, "participatingSentinels");
        if (quorum <= 0) {
            throw new IllegalArgumentException("quorum must be > 0");
        }
        List<NodeId> voters = participatingSentinels.stream()
                .distinct()
                .sorted(java.util.Comparator.comparing(NodeId::toString))
                .toList();
        if (voters.size() < quorum) {
            return Optional.empty();
        }

        NodeId leaderId = voters.getFirst();
        long term = currentTerm.incrementAndGet();
        Map<NodeId, NodeId> votes = new LinkedHashMap<>();
        for (NodeId voter : voters) {
            votes.put(voter, leaderId);
        }
        return Optional.of(new ElectionResult(term, leaderId, Map.copyOf(votes)));
    }

    public record ElectionResult(long term, NodeId leaderId, Map<NodeId, NodeId> votes) {
        public ElectionResult {
            Objects.requireNonNull(leaderId, "leaderId");
            Objects.requireNonNull(votes, "votes");
        }
    }
}
