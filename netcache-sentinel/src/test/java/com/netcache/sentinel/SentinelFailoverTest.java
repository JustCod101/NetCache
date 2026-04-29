package com.netcache.sentinel;

import com.netcache.client.routing.TopologyCache;
import com.netcache.cluster.ClusterTopology;
import com.netcache.cluster.NodeEndpoint;
import com.netcache.cluster.sentinel.FailoverCoordinator;
import com.netcache.cluster.sentinel.SentinelNode;
import com.netcache.common.NodeId;
import com.netcache.common.NodeRole;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SentinelFailoverTest {
    @Test
    void promotesHighestOffsetSlaveAndRewritesTopology() {
        NodeEndpoint master = endpoint(1, 7001, NodeRole.MASTER, null);
        NodeEndpoint bestSlave = endpoint(2, 7002, NodeRole.SLAVE, master.nodeId());
        NodeEndpoint otherSlave = endpoint(3, 7003, NodeRole.SLAVE, master.nodeId());

        ClusterTopology topology = topology(master, bestSlave, otherSlave);
        SentinelNode sentinel = sentinel(topology);

        sentinel.recordPing(master.nodeId(), true, 0L);
        sentinel.observePeerSdown(master.nodeId(), nodeId(101));

        Optional<FailoverCoordinator.FailoverResult> failover = sentinel.tryFailover(
                master.nodeId(),
                Map.of(bestSlave.nodeId(), 20L, otherSlave.nodeId(), 10L),
                Map.of(bestSlave.nodeId(), 1, otherSlave.nodeId(), 100),
                5_001L);

        assertThat(failover).isPresent();
        assertThat(failover.orElseThrow().promotedMasterId()).isEqualTo(bestSlave.nodeId());
        assertThat(topology.epoch()).isEqualTo(2L);
        assertThat(topology.node(bestSlave.nodeId())).hasValueSatisfying(node -> {
            assertThat(node.role()).isEqualTo(NodeRole.MASTER);
            assertThat(node.masterId()).isNull();
        });
        assertThat(topology.node(master.nodeId())).hasValueSatisfying(node -> {
            assertThat(node.role()).isEqualTo(NodeRole.SLAVE);
            assertThat(node.masterId()).isEqualTo(bestSlave.nodeId());
        });
        assertThat(topology.node(otherSlave.nodeId())).hasValueSatisfying(node -> {
            assertThat(node.role()).isEqualTo(NodeRole.SLAVE);
            assertThat(node.masterId()).isEqualTo(bestSlave.nodeId());
        });
    }

    @Test
    void clientRoutesWritesToPromotedMasterWithinThreeSeconds() throws Exception {
        NodeEndpoint master = endpoint(11, 7101, NodeRole.MASTER, null);
        NodeEndpoint promoted = endpoint(12, 7102, NodeRole.SLAVE, master.nodeId());
        NodeEndpoint follower = endpoint(13, 7103, NodeRole.SLAVE, master.nodeId());

        ClusterTopology topology = topology(master, promoted, follower);
        TopologyCache topologyCache = new TopologyCache(List.of(master.address()));
        topologyCache.updateTopology(topology);

        InMemoryCluster cluster = new InMemoryCluster(topology);
        byte[] key = bytes("alpha");
        cluster.write(topologyCache, key, bytes("before"));

        SentinelNode sentinel = sentinel(topology);
        sentinel.recordPing(master.nodeId(), true, 0L);
        sentinel.observePeerSdown(master.nodeId(), nodeId(201));
        cluster.markDown(master.nodeId());

        Optional<FailoverCoordinator.FailoverResult> failover = sentinel.tryFailover(
                master.nodeId(),
                Map.of(promoted.nodeId(), 99L, follower.nodeId(), 10L),
                Map.of(promoted.nodeId(), 10, follower.nodeId(), 5),
                5_001L);
        assertThat(failover).isPresent();
        topologyCache.updateTopology(topology);

        awaitWrite(cluster, topologyCache, key, bytes("after"), Duration.ofSeconds(3));

        assertThat(cluster.valueOn(promoted.nodeId(), key)).contains(bytes("after"));
        assertThat(topology.route(key).nodeId()).isEqualTo(promoted.nodeId());
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

    private static SentinelNode sentinel(ClusterTopology topology) {
        return new SentinelNode(
                nodeId(100),
                List.of(nodeId(101), nodeId(102)),
                2,
                1_000L,
                5_000L,
                10_000L,
                topology);
    }

    private static ClusterTopology topology(NodeEndpoint... nodes) {
        ClusterTopology topology = new ClusterTopology();
        assertThat(topology.apply(1L, List.of(nodes))).isTrue();
        return topology;
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
            String targetAddress = topologyCache.route(key);
            NodeEndpoint target = topology.nodeMap().values().stream()
                    .filter(node -> node.address().equals(targetAddress))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("missing node for address " + targetAddress));
            if (!Boolean.TRUE.equals(liveNodes.get(target.nodeId()))) {
                throw new IllegalStateException("target node is down: " + target.nodeId());
            }
            if (target.role() != NodeRole.MASTER) {
                throw new IllegalStateException("writes must target master but routed to " + target.nodeId());
            }
            values.get(target.nodeId()).put(new String(key, StandardCharsets.UTF_8), value.clone());
        }

        private Optional<byte[]> valueOn(NodeId nodeId, byte[] key) {
            return Optional.ofNullable(values.get(nodeId).get(new String(key, StandardCharsets.UTF_8)));
        }
    }
}
