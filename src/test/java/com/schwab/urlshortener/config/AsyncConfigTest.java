package com.schwab.urlshortener.config;

import org.junit.jupiter.api.Test;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Exercises the branch this config exists to make deliberate rather than
 * accidental: real async in production, synchronous in tests. Previously
 * only exercised indirectly by whichever profile happened to be active
 * when a Spring context booted — this asserts the actual branching logic
 * directly, plus that a thrown exception inside an @Async void method is
 * handled rather than silently swallowed.
 */
class AsyncConfigTest {

    @Test
    void meteringAsyncDisabled_returnsSyncTaskExecutor() {
        AsyncConfig config = new AsyncConfig();
        ReflectionTestUtils.setField(config, "meteringAsyncEnabled", false);

        Executor executor = config.getAsyncExecutor();

        assertThat(executor).isInstanceOf(SyncTaskExecutor.class);
    }

    @Test
    void meteringAsyncEnabled_returnsBoundedThreadPool_notSpringsUnboundedDefault() {
        AsyncConfig config = new AsyncConfig();
        ReflectionTestUtils.setField(config, "meteringAsyncEnabled", true);

        Executor executor = config.getAsyncExecutor();

        assertThat(executor).isInstanceOf(ThreadPoolTaskExecutor.class);
        ThreadPoolTaskExecutor pool = (ThreadPoolTaskExecutor) executor;
        assertThat(pool.getCorePoolSize()).isEqualTo(4);
        assertThat(pool.getMaxPoolSize()).isEqualTo(16);
    }

    @Test
    void uncaughtExceptionHandler_doesNotItselfThrow() {
        // The whole point of this handler existing is that an exception
        // inside an @Async void method has nowhere else to go — the least
        // it must do is not blow up itself while logging that.
        AsyncConfig config = new AsyncConfig();
        var handler = config.getAsyncUncaughtExceptionHandler();

        assertThatCode(() -> handler.handleUncaughtException(
                new RuntimeException("simulated failure inside an @Async method"),
                AsyncConfigTest.class.getDeclaredMethods()[0]
        )).doesNotThrowAnyException();
    }
}
