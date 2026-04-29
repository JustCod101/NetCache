package com.netcache.client.retry;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

/**
 * 客户端重试策略，像故障时的“减震器”，负责在请求失败后按指数退避节奏重新尝试，避免瞬时故障直接把调用打死。
 * <p>
 * 之所以需要它，是因为分布式网络故障常常是短暂且抖动的；如果没有重试层，偶发写失败、短时超时或节点切换都会直接暴露给业务调用方。
 * <p>
 * 上游由 {@link com.netcache.client.DefaultNetCacheClient} 调用；下游包裹任意返回 {@link CompletableFuture} 的异步操作。
 * <p>
 * 线程安全说明：该类是线程安全的。它只保存不可变的最大重试次数，退避与抖动都在方法局部计算。
 * <p>
 * 典型用例：
 * <pre>{@code
 * RetryPolicy retryPolicy = new RetryPolicy(3);
 * CompletableFuture<Response> future = retryPolicy.execute(() -> requestRouter.route(request));
 * Response response = future.join();
 * }</pre>
 */
public final class RetryPolicy {
    private final int maxRetries;

    /**
     * 创建一个重试策略。
     *
     * @param maxRetries 表示失败后的最大重试次数，必须大于等于 0
     * @throws IllegalArgumentException 当 {@code maxRetries} 为负数时抛出
     * @implNote 该值只控制补偿尝试次数，不包含第一次正常调用。
     */
    public RetryPolicy(int maxRetries) {
        if (maxRetries < 0) {
            throw new IllegalArgumentException("maxRetries must be non-negative");
        }
        this.maxRetries = maxRetries;
    }

    /**
     * 执行一个可重试的异步操作。
     *
     * @param operation 表示真正的异步操作提供者，不能为空
     * @param <T> 表示 future 最终结果类型
     * @return 返回一个聚合 future；只要某次尝试成功就成功完成，全部耗尽则异常完成
     * @throws NullPointerException 当 {@code operation} 为 {@code null} 时抛出
     * @implNote 当前实现采用递归回调驱动重试，而不是阻塞睡眠线程。
     */
    public <T> CompletableFuture<T> execute(Supplier<CompletableFuture<T>> operation) {
        Objects.requireNonNull(operation, "operation");
        CompletableFuture<T> result = new CompletableFuture<>();
        attempt(operation, result, 0);
        return result;
    }

    /**
     * 计算某次重试前的退避时间。
     *
     * @param attempt 表示当前是第几次重试，从 0 开始
     * @return 返回需要等待的毫秒数，已包含随机抖动
     * @implNote 算法为指数退避：base=50ms，factor=2，cap=2000ms，并附加 ±20% jitter 以减少雷鸣羊群效应。
     */
    public long backoffMillis(int attempt) {
        long base = 50L * (1L << Math.min(attempt, 6));
        long capped = Math.min(base, 2000L);
        long jitter = ThreadLocalRandom.current().nextLong(-capped / 5, capped / 5 + 1);
        return capped + jitter;
    }

    /**
     * 递归驱动一次异步尝试，并在失败时按策略安排后续重试。
     *
     * @param operation 表示真正的异步操作提供者
     * @param result 表示聚合后的最终结果 future
     * @param attempt 表示当前尝试序号，从 0 开始
     * @param <T> 表示 future 最终结果类型
     * @implNote
     * 重试流程：
     * <pre>
     * try:
     *   result = sendRequest(key, value)
     * catch Exception:
     *   if attempt < maxRetries:
     *     sleep(backoff(attempt) + jitter)
     *     goto try
     *   else:
     *     throw
     * </pre>
     */
    private <T> void attempt(Supplier<CompletableFuture<T>> operation, CompletableFuture<T> result, int attempt) {
        try {
            operation.get().whenComplete((value, error) -> {
                if (error == null) {
                    result.complete(value);
                } else if (attempt >= maxRetries) {
                    result.completeExceptionally(error);
                } else {
                    CompletableFuture.delayedExecutor(backoffMillis(attempt), java.util.concurrent.TimeUnit.MILLISECONDS)
                            .execute(() -> attempt(operation, result, attempt + 1));
                }
            });
        } catch (RuntimeException ex) {
            result.completeExceptionally(ex);
        }
    }
}
