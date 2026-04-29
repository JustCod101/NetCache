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
import org.openjdk.jmh.annotations.Warmup;

import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 1, time = 1)
@Measurement(iterations = 2, time = 1)
@Fork(1)
public class ThroughputBenchmark {
    @Benchmark
    public void set(BenchmarkState state) {
        state.node.client().set(state.node.nextKey(), state.node.value());
    }

    @Benchmark
    public byte[] get(BenchmarkState state) {
        return state.node.client().get(state.node.nextKey());
    }

    @State(Scope.Benchmark)
    public static class BenchmarkState {
        LocalBenchmarkNode node;

        @Setup(Level.Trial)
        public void setUp() throws Exception {
            node = new LocalBenchmarkNode(256, 128);
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
