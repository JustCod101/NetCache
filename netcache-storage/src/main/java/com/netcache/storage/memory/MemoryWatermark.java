package com.netcache.storage.memory;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.util.Objects;
import java.util.function.DoubleSupplier;

public final class MemoryWatermark {
    private final double highWatermark;
    private final double dangerWatermark;
    private final DoubleSupplier usageSupplier;

    public MemoryWatermark(double highWatermark, double dangerWatermark) {
        this(highWatermark, dangerWatermark, MemoryWatermark::heapUsageRatio);
    }

    public MemoryWatermark(double highWatermark, double dangerWatermark, DoubleSupplier usageSupplier) {
        if (highWatermark <= 0 || highWatermark >= 1) {
            throw new IllegalArgumentException("highWatermark must be between 0 and 1");
        }
        if (dangerWatermark <= highWatermark || dangerWatermark >= 1) {
            throw new IllegalArgumentException("dangerWatermark must be greater than highWatermark and less than 1");
        }
        this.highWatermark = highWatermark;
        this.dangerWatermark = dangerWatermark;
        this.usageSupplier = Objects.requireNonNull(usageSupplier, "usageSupplier");
    }

    public static MemoryWatermark defaults() {
        return new MemoryWatermark(0.85, 0.92);
    }

    public boolean isHigh() {
        return usageSupplier.getAsDouble() >= highWatermark;
    }

    public boolean isDanger() {
        return usageSupplier.getAsDouble() >= dangerWatermark;
    }

    public double usageRatio() {
        return usageSupplier.getAsDouble();
    }

    private static double heapUsageRatio() {
        MemoryUsage usage = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        long max = usage.getMax();
        if (max <= 0) {
            return 0.0;
        }
        return (double) usage.getUsed() / max;
    }
}
