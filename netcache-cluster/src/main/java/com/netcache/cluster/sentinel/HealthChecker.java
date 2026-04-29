package com.netcache.cluster.sentinel;

import com.netcache.common.NodeId;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

public final class HealthChecker {
    private final long pingIntervalMs;
    private final long sdownAfterMs;
    private final ConcurrentHashMap<NodeId, Long> lastHealthyAt = new ConcurrentHashMap<>();

    public HealthChecker(long pingIntervalMs, long sdownAfterMs) {
        if (pingIntervalMs <= 0) {
            throw new IllegalArgumentException("pingIntervalMs must be > 0");
        }
        if (sdownAfterMs <= 0) {
            throw new IllegalArgumentException("sdownAfterMs must be > 0");
        }
        this.pingIntervalMs = pingIntervalMs;
        this.sdownAfterMs = sdownAfterMs;
    }

    public long pingIntervalMs() {
        return pingIntervalMs;
    }

    public long sdownAfterMs() {
        return sdownAfterMs;
    }

    public void recordPing(NodeId nodeId, boolean healthy, long nowMs) {
        Objects.requireNonNull(nodeId, "nodeId");
        if (healthy) {
            lastHealthyAt.put(nodeId, nowMs);
        }
    }

    public boolean isSubjectivelyDown(NodeId nodeId, long nowMs) {
        Objects.requireNonNull(nodeId, "nodeId");
        Long lastHealthy = lastHealthyAt.get(nodeId);
        return lastHealthy == null || nowMs - lastHealthy >= sdownAfterMs;
    }
}
