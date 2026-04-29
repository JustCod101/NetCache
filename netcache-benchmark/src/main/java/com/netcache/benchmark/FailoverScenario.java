package com.netcache.benchmark;

import com.netcache.client.routing.TopologyCache;
import com.netcache.cluster.ClusterTopology;
import com.netcache.cluster.NodeEndpoint;
import com.netcache.cluster.sentinel.FailoverCoordinator;
import com.netcache.cluster.sentinel.SentinelNode;
import com.netcache.common.NodeId;
import com.netcache.common.NodeRole;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class FailoverScenario {
    public Result run() throws Exception {
        NodeEndpoint master = endpoint(1, 7301, NodeRole.MASTER, null);
        NodeEndpoint promoted = endpoint(2, 7302, NodeRole.SLAVE, master.nodeId());
        NodeEndpoint follower = endpoint(3, 7303, NodeRole.SLAVE, master.nodeId());

        ClusterTopology topology = new ClusterTopology();
        topology.apply(1L, List.of(master, promoted, follower));

        TopologyCache topologyCache = new TopologyCache(List.of(master.address()));
        topologyCache.updateTopology(topology);
        InMemoryCluster cluster = new InMemoryCluster(topology);
        cluster.write(topologyCache, bytes("alpha"), bytes("before"));

        SentinelNode sentinel = new SentinelNode(
                nodeId(100),
                List.of(nodeId(101), nodeId(102)),
                2,
                1_000L,
                5_000L,
                10_000L,
                topology);
        sentinel.recordPing(master.nodeId(), true, 0L);
        sentinel.observePeerSdown(master.nodeId(), nodeId(101));
        cluster.markDown(master.nodeId());

        long startedAt = System.nanoTime();
        Optional<FailoverCoordinator.FailoverResult> failover = sentinel.tryFailover(
                master.nodeId(),
                Map.of(promoted.nodeId(), 99L, follower.nodeId(), 10L),
                Map.of(promoted.nodeId(), 10, follower.nodeId(), 5),
                5_001L);
        if (failover.isEmpty()) {
            throw new IllegalStateException("failover did not complete");
        }
        topologyCache.updateTopology(topology);
        awaitWrite(cluster, topologyCache, bytes("alpha"), bytes("after"), Duration.ofSeconds(3));
        long durationMs = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
        return new Result(durationMs, failover.orElseThrow().promotedMasterId(), topology.epoch());
    }

    private static void awaitWrite(InMemoryCluster cluster,
                                   TopologyCache topologyCache,
                                   byte[] key,
                                   byte[] value,
                                   Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            try {
                cluster.write(topologyCache, key, value);
                return;
            } catch (IllegalStateException ignored) {
                Thread.sleep(10L);
            }
        }
        cluster.write(topologyCache, key, value);
    }

    public record Result(long durationMs, NodeId promotedMasterId, long epoch) {
    }

    private static NodeEndpoint endpoint(long id, int port, NodeRole role, NodeId masterId) {
        return new NodeEndpoint(nodeId(id), "127.0.0.1", port, role, masterId);
    }

    private static NodeId nodeId(long id) {
        return new NodeId(new UUID(0L, id));
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static final class InMemoryCluster {
        private final ClusterTopology topology;
        private final Map<NodeId, Map<String, byte[]>> values = new HashMap<>();
        private final Map<NodeId, Boolean> liveNodes = new HashMap<>();

        private InMemoryCluster(ClusterTopology topology) {
            this.topology = topology;
            for (NodeEndpoint node : topology.nodes()) {
                values.put(node.nodeId(), new HashMap<>());
                liveNodes.put(node.nodeId(), true);
            }
        }

        private void markDown(NodeId nodeId) {
            liveNodes.put(nodeId, false);
        }

        private void write(TopologyCache topologyCache, byte[] key, byte[] value) {
            String address = topologyCache.route(key);
            NodeEndpoint target = topology.nodeMap().values().stream()
                    .filter(node -> node.address().equals(address))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("missing node for address " + address));
            if (!Boolean.TRUE.equals(liveNodes.get(target.nodeId()))) {
                throw new IllegalStateException("target node is down: " + target.nodeId());
            }
            if (target.role() != NodeRole.MASTER) {
                throw new IllegalStateException("writes must target master");
            }
            values.get(target.nodeId()).put(new String(key, StandardCharsets.UTF_8), value.clone());
        }
    }
}
