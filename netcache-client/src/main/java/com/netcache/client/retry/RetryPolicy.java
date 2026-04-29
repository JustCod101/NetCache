package com.netcache.client.retry;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

public final class RetryPolicy {
    private final int maxRetries;

    public RetryPolicy(int maxRetries) {
        if (maxRetries < 0) {
            throw new IllegalArgumentException("maxRetries must be non-negative");
        }
        this.maxRetries = maxRetries;
    }

    public <T> CompletableFuture<T> execute(Supplier<CompletableFuture<T>> operation) {
        Objects.requireNonNull(operation, "operation");
        CompletableFuture<T> result = new CompletableFuture<>();
        attempt(operation, result, 0);
        return result;
    }

    public long backoffMillis(int attempt) {
        long base = 50L * (1L << Math.min(attempt, 6));
        long capped = Math.min(base, 2000L);
        long jitter = ThreadLocalRandom.current().nextLong(-capped / 5, capped / 5 + 1);
        return capped + jitter;
    }

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
