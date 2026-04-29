package com.netcache.client.routing;

import com.netcache.cluster.ClusterTopology;
import com.netcache.cluster.NodeEndpoint;
import com.netcache.common.NodeId;
import com.netcache.common.NodeRole;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TopologyCacheTest {
    @Test
    void routesKeysThroughClusterTopologyWhenAvailable() {
        TopologyCache cache = new TopologyCache(List.of("seed:7001"));
        ClusterTopology topology = new ClusterTopology();
        NodeEndpoint first = endpoint(1, 7001);
        NodeEndpoint second = endpoint(2, 7002);
        topology.apply(1, List.of(first, second));

        new MovedHandler(cache).refresh(topology);

        String seed = cache.route("abc".getBytes(StandardCharsets.UTF_8));

        assertThat(seed).isIn(first.address(), second.address());
        assertThat(cache.routeEndpoint("abc".getBytes(StandardCharsets.UTF_8))).isPresent();
    }

    private static NodeEndpoint endpoint(long id, int port) {
        return new NodeEndpoint(new NodeId(new UUID(0L, id)), "127.0.0.1", port, NodeRole.MASTER, null);
    }
}
