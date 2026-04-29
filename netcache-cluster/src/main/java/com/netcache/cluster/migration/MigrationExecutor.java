package com.netcache.cluster.migration;

import java.util.List;

public final class MigrationExecutor {
    public int plannedKeyPerSecondLimit() {
        return 5_000;
    }

    public int executePlan(List<KeyMigration> migrations) {
        return migrations.size();
    }
}
