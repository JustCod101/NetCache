package com.netcache.cluster;

import com.netcache.common.NodeId;

public record VirtualNode(NodeId nodeId, int index, long hash) {
}
