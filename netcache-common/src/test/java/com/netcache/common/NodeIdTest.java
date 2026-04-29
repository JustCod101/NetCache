package com.netcache.common;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class NodeIdTest {
    @Test
    void wrapsUuidAndParsesString() {
        UUID uuid = UUID.randomUUID();

        assertThat(NodeId.fromString(uuid.toString())).isEqualTo(new NodeId(uuid));
    }

    @Test
    void rejectsNullUuid() {
        assertThatNullPointerException().isThrownBy(() -> new NodeId(null));
    }
}
