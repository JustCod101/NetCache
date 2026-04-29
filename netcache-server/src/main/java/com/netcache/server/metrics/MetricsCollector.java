package com.netcache.server.metrics;

import java.util.concurrent.atomic.LongAdder;

public final class MetricsCollector {
    private final LongAdder requests = new LongAdder();

    public void recordRequest() {
        requests.increment();
    }

    public long requestCount() {
        return requests.sum();
    }
}
