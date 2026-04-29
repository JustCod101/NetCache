package com.netcache.cluster;

import com.netcache.common.NodeId;
import com.netcache.common.NodeRole;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ClusterTopologyTest {
    @Test
    void ignoresStaleEpochAndRoutesToMasterEndpoint() {
        ClusterTopology topology = new ClusterTopology();
        NodeEndpoint first = endpoint(1, "127.0.0.1", 7001);
        NodeEndpoint second = endpoint(2, "127.0.0.1", 7002);

        assertThat(topology.apply(2, List.of(first, second))).isTrue();
        assertThat(topology.apply(1, List.of(first))).isFalse();

        NodeEndpoint routed = topology.route("abc".getBytes(StandardCharsets.UTF_8));
        assertThat(routed).isIn(first, second);
        assertThat(topology.epoch()).isEqualTo(2L);
        assertThat(topology.nodes()).hasSize(2);
    }

    private static NodeEndpoint endpoint(long id, String host, int port) {
        return new NodeEndpoint(new NodeId(new UUID(0L, id)), host, port, NodeRole.MASTER, null);
    }
}
