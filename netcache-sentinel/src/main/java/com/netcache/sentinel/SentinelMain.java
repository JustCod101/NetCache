package com.netcache.sentinel;

import java.util.concurrent.CountDownLatch;

public final class SentinelMain {
    private SentinelMain() {
    }

    public static void main(String[] args) throws InterruptedException {
        // Phase 8 keeps Sentinel logic in netcache-cluster for reuse.
        // This module remains the dedicated process entrypoint.
        String sentinelId = System.getProperty("netcache.sentinel.id", "sentinel-1");
        int quorum = Integer.getInteger("netcache.sentinel.quorum", 2);
        System.out.println("Sentinel process started: id=" + sentinelId + ", quorum=" + quorum);
        new CountDownLatch(1).await();
    }
}
