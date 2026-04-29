package com.netcache.cluster.migration;

import com.netcache.common.NodeId;

public record KeyMigration(NodeId source, NodeId target, long startExclusive, long endInclusive) {
}
