package com.netcache.benchmark;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BenchmarkSmokeTest {
    @Test
    void throughputBenchmarkStateSupportsSetAndGet() throws Exception {
        ThroughputBenchmark benchmark = new ThroughputBenchmark();
        ThroughputBenchmark.BenchmarkState state = new ThroughputBenchmark.BenchmarkState();
        state.setUp();
        try {
            benchmark.set(state);
            assertThat(benchmark.get(state)).isNotNull();
        } finally {
            state.tearDown();
        }
    }

    @Test
    void latencyBenchmarkStateSupportsSetAndGet() throws Exception {
        LatencyBenchmark benchmark = new LatencyBenchmark();
        LatencyBenchmark.BenchmarkState state = new LatencyBenchmark.BenchmarkState();
        state.setUp();
        try {
            benchmark.set(state);
            assertThat(benchmark.get(state)).isNotNull();
        } finally {
            state.tearDown();
        }
    }

    @Test
    void failoverScenarioCompletesWithinThreeSeconds() throws Exception {
        FailoverScenario.Result result = new FailoverScenario().run();

        assertThat(result.durationMs()).isLessThan(3_000L);
        assertThat(result.epoch()).isEqualTo(2L);
    }
}
