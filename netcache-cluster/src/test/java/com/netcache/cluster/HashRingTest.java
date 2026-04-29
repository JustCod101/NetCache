package com.netcache.cluster;

import com.netcache.common.NodeId;
import com.netcache.cluster.migration.KeyMigration;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class HashRingTest {
    @Test
    void distributesOneHundredThousandKeysAcrossThreeNodesWithinFivePercent() {
        HashRing ring = new HashRing();
        NodeId first = node(1);
        NodeId second = node(2);
        NodeId third = node(3);
        ring.addNode(first);
        ring.addNode(second);
        ring.addNode(third);

        Map<NodeId, Integer> counts = new HashMap<>();
        for (int i = 0; i < 100_000; i++) {
            NodeId owner = ring.routeOf(("key-" + i).getBytes(StandardCharsets.UTF_8));
            counts.merge(owner, 1, Integer::sum);
        }

        double expected = 100_000.0 / 3.0;
        for (int count : counts.values()) {
            double deviation = Math.abs(count - expected) / expected;
            assertThat(deviation).isLessThan(0.05);
        }
    }

    @Test
    void addAndRemoveNodeProduceMigrationRanges() {
        HashRing ring = new HashRing();
        NodeId first = node(1);
        NodeId second = node(2);

        assertThat(ring.addNode(first)).isEmpty();
        List<KeyMigration> addMigrations = ring.addNode(second);
        List<KeyMigration> removeMigrations = ring.removeNode(first);

        assertThat(addMigrations).isNotEmpty();
        assertThat(removeMigrations).isNotEmpty();
        assertThat(ring.contains(second)).isTrue();
        assertThat(ring.contains(first)).isFalse();
    }

    private static NodeId node(long value) {
        return new NodeId(new UUID(0L, value));
    }
}
