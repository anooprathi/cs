package com.schwab.urlshortener.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.lang.reflect.Method;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Enables {@code @Async} and gives it a dedicated, bounded thread pool
 * rather than Spring's default {@code SimpleAsyncTaskExecutor} (which is
 * unbounded — a genuinely dangerous default in production, since a burst
 * of async work can spawn unlimited threads and exhaust memory/OS thread
 * limits with no backpressure at all).
 *
 * Used specifically by {@link com.schwab.urlshortener.billing.UsageMeteringService}
 * to keep usage-metering writes off the hot redirect path's response
 * latency — see that class's Javadoc for the consistency trade-off this
 * implies and why it's an acceptable one here.
 *
 * Implementing AsyncConfigurer (rather than just declaring a bean) also
 * lets us install a handler for exceptions thrown inside {@code @Async
 * void} methods: those can't propagate back to the caller by definition
 * (the caller has already moved on), so without this they would
 * otherwise vanish silently into the executor's thread.
 *
 * {@code app.async.metering.enabled=false} (set in application-test.properties)
 * swaps the real thread pool for a same-thread SyncTaskExecutor —
 * deliberately, not a workaround: integration tests that create a link or
 * hit a redirect and then immediately assert on the resulting usage count
 * would otherwise be racing the async write, exactly the kind of flaky
 * test a genuine production system should not ship. The metering logic's
 * own correctness is unit-tested directly (UsageMeteringServiceTest); this
 * setting only removes the *timing* nondeterminism from tests that check
 * its downstream effect, without weakening what either layer verifies.
 */
@Configuration
@EnableAsync
public class AsyncConfig implements AsyncConfigurer {

    @Value("${app.async.metering.enabled:true}")
    private boolean meteringAsyncEnabled;

    @Override
    @Bean(name = "meteringTaskExecutor")
    public Executor getAsyncExecutor() {
        if (!meteringAsyncEnabled) {
            return new SyncTaskExecutor();
        }

        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        // Sized for "many small, fast writes," not CPU-bound work — usage
        // metering is a single atomic UPDATE (or the rare insert-on-first-use),
        // not a computation, so a modest pool comfortably absorbs bursts without
        // over-provisioning threads that would mostly sit idle.
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(16);
        executor.setQueueCapacity(500);
        executor.setThreadNamePrefix("metering-async-");
        // CallerRunsPolicy: if the queue is ever actually full, fall back to
        // running the metering call synchronously on the caller's thread
        // rather than silently dropping it (Spring's default AbortPolicy
        // would throw and lose the usage record) or unboundedly queuing
        // (which would just delay an OOM instead of preventing one).
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }

    @Override
    public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
        return new LoggingAsyncUncaughtExceptionHandler();
    }

    @Slf4j
    static class LoggingAsyncUncaughtExceptionHandler implements AsyncUncaughtExceptionHandler {
        @Override
        public void handleUncaughtException(Throwable ex, Method method, Object... params) {
            log.error("Uncaught exception in @Async method {}: {}", method.getName(), ex.getMessage(), ex);
        }
    }
}
