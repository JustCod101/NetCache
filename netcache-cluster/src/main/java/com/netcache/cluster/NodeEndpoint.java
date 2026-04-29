package com.netcache.cluster;

import com.netcache.common.NodeId;
import com.netcache.common.NodeRole;

import java.util.Objects;

public record NodeEndpoint(NodeId nodeId, String host, int port, NodeRole role, NodeId masterId) {
    public NodeEndpoint {
        Objects.requireNonNull(nodeId, "nodeId");
        Objects.requireNonNull(host, "host");
        Objects.requireNonNull(role, "role");
        if (port <= 0 || port > 65535) {
            throw new IllegalArgumentException("port must be 1..65535");
        }
    }

    public String address() {
        return host + ':' + port;
    }
}
