package com.netcache.client.retry;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class RetryPolicyTest {
    @Test
    void retriesFailedOperationUntilSuccess() {
        RetryPolicy retryPolicy = new RetryPolicy(2);
        AtomicInteger attempts = new AtomicInteger();

        CompletableFuture<String> result = retryPolicy.execute(() -> {
            if (attempts.getAndIncrement() == 0) {
                CompletableFuture<String> failed = new CompletableFuture<>();
                failed.completeExceptionally(new IllegalStateException("try again"));
                return failed;
            }
            return CompletableFuture.completedFuture("ok");
        });

        assertThat(result).succeedsWithin(java.time.Duration.ofSeconds(1)).isEqualTo("ok");
        assertThat(attempts).hasValue(2);
    }
}
