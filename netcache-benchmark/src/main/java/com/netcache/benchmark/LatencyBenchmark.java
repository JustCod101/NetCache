package com.netcache.benchmark;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;

import java.util.concurrent.TimeUnit;

/**
 * Measures single-node GET/SET latency distribution over one real TCP connection.
 * Pipeline is disabled because every synchronous client call waits for its response.
 */
@BenchmarkMode(Mode.SampleTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 3)
@Measurement(iterations = 5, time = 5)
@Fork(2)
@Threads(1)
public class LatencyBenchmark {

    private static final int KEY_COUNT = 4096;
    private static final int VALUE_SIZE = 1024; // 1KB as per architecture spec

    @Benchmark
    public byte[] get(BenchmarkState state) {
        return state.node.client().get(state.node.nextKey());
    }

    @Benchmark
    public void set(BenchmarkState state) {
        state.node.client().set(state.node.nextKey(), state.node.value());
    }

    @State(Scope.Benchmark)
    public static class BenchmarkState {
        LocalBenchmarkNode node;

        @Setup(Level.Trial)
        public void setUp() throws Exception {
            node = new LocalBenchmarkNode(KEY_COUNT, VALUE_SIZE, 1);
            node.start();
        }

        @TearDown(Level.Trial)
        public void tearDown() {
            if (node != null) {
                node.close();
            }
        }
    }
}
