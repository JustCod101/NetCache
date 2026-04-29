package com.netcache.storage.memory;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.util.Objects;
import java.util.function.DoubleSupplier;

/**
 * 堆内存水位线探测器，相当于存储引擎的液位报警器。
 * 它把 JVM 堆使用率翻译成“该提前腾地方了”或“必须拒写”两种信号。
 * 没有它，缓存只会一路写到 OOM，退化成硬崩而不是受控降级。
 *
 * <p>上游由 {@code StorageEngine} 在写入前查询；下游默认读取 JVM
 * 堆信息，也支持注入自定义采样器做测试。</p>
 *
 * <p>线程安全说明：线程安全。对象自身不可变，读操作只委托给
 * {@link DoubleSupplier} 取样。</p>
 *
 * <pre>{@code
 * MemoryWatermark watermark = MemoryWatermark.defaults();
 * if (watermark.isDanger()) {
 *     throw new IllegalStateException();
 * }
 * }</pre>
 */
public final class MemoryWatermark {
    /** 高水位阈值，达到后应同步淘汰。 */
    private final double highWatermark;
    /** 危险水位阈值，达到后应直接拒写。 */
    private final double dangerWatermark;
    /** 实际堆使用率采样函数。 */
    private final DoubleSupplier usageSupplier;

    /**
     * 使用默认 JVM 堆采样器创建水位线规则。
     *
     * @param highWatermark 高水位阈值，取值区间为 (0, 1)
     * @param dangerWatermark 危险水位阈值，必须大于高水位且小于 1
     * @throws IllegalArgumentException 当阈值关系非法时抛出
     */
    public MemoryWatermark(double highWatermark, double dangerWatermark) {
        this(highWatermark, dangerWatermark, MemoryWatermark::heapUsageRatio);
    }

    /**
     * 创建一个可注入采样器的水位线规则。
     *
     * @param highWatermark 高水位阈值，超过后建议先淘汰再写
     * @param dangerWatermark 危险水位阈值，超过后应立即拒写
     * @param usageSupplier 返回当前堆使用率的函数，值通常在 0 到 1 之间
     * @throws IllegalArgumentException 当阈值不在合法区间或前后顺序错误时抛出
     * @throws NullPointerException 当 {@code usageSupplier} 为 {@code null}
     *     时抛出
     */
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

    /**
     * 创建默认水位线配置。
     *
     * @return 高水位 85%、危险水位 92% 的默认实例
     */
    public static MemoryWatermark defaults() {
        return new MemoryWatermark(0.85, 0.92);
    }

    /**
     * 判断是否已经跨过高水位。
     *
     * @return {@code true} 表示应先尝试淘汰再继续写入
     */
    public boolean isHigh() {
        return usageSupplier.getAsDouble() >= highWatermark;
    }

    /**
     * 判断是否已经跨过危险水位。
     *
     * @return {@code true} 表示应立即拒绝新写入
     */
    public boolean isDanger() {
        return usageSupplier.getAsDouble() >= dangerWatermark;
    }

    /**
     * 读取当前堆使用率。
     *
     * @return 当前采样到的堆使用率，通常在 0 到 1 之间
     */
    public double usageRatio() {
        return usageSupplier.getAsDouble();
    }

    /**
     * 读取 JVM 堆使用率。
     *
     * @return 已用堆 / 最大堆；如果 JVM 未给出最大堆，则返回 0.0
     * @implNote 当 max 不可得时返回 0.0，是为了让保护逻辑保守失效，
     *     不至于因为异常元数据把系统直接判成超限。
     */
    private static double heapUsageRatio() {
        MemoryUsage usage = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        long max = usage.getMax();
        if (max <= 0) {
            return 0.0;
        }
        return (double) usage.getUsed() / max;
    }
}
