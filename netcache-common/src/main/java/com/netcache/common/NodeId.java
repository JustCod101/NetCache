package com.netcache.common;

import java.util.Objects;
import java.util.UUID;

public record NodeId(UUID id) {
    public NodeId {
        Objects.requireNonNull(id, "id");
    }

    public static NodeId random() {
        return new NodeId(UUID.randomUUID());
    }

    public static NodeId fromString(String value) {
        return new NodeId(UUID.fromString(Objects.requireNonNull(value, "value")));
    }

    @Override
    public String toString() {
        return id.toString();
    }
}
