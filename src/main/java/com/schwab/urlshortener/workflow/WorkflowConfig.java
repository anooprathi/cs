package com.schwab.urlshortener.workflow;

import com.schwab.urlshortener.workflow.agents.CommandTestRunner;
import com.schwab.urlshortener.workflow.agents.PlanVerificationTestRunner;
import com.schwab.urlshortener.workflow.agents.TestRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Thread pools and pluggable parts of the workflow module.
 *
 * <ul>
 *   <li>{@code workflowDriverExecutor}: one thread per run being driven asynchronously. It is bounded, and a full
 *       queue rejects the start/resume with 503 rather than queueing unboundedly.</li>
 *   <li>{@code workflowTaskExecutor}: task workers shared by all runs. Each run additionally caps its own
 *       in-flight tasks at {@code app.workflow.max-parallelism}. CallerRuns on saturation degrades to running
 *       the task on the driver thread rather than dropping it.</li>
 *   <li>{@code workflowAgentExecutor}: runs each agent invocation so the task worker can enforce the per-attempt
 *       timeout. One agent thread per in-flight task, so it's bounded by the task pool.</li>
 * </ul>
 */
@Configuration
public class WorkflowConfig {

    @Bean(name = "workflowDriverExecutor")
    public ThreadPoolTaskExecutor workflowDriverExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(20);
        executor.setThreadNamePrefix("workflow-driver-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.initialize();
        return executor;
    }

    @Bean(name = "workflowTaskExecutor")
    public ThreadPoolTaskExecutor workflowTaskExecutor(WorkflowProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        int size = Math.max(4, properties.maxParallelism() * 2);
        executor.setCorePoolSize(size);
        executor.setMaxPoolSize(size * 2);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("workflow-task-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }

    @Bean(name = "workflowAgentExecutor", destroyMethod = "shutdownNow")
    public ExecutorService workflowAgentExecutor() {
        AtomicInteger n = new AtomicInteger();
        ThreadFactory factory = r -> {
            Thread t = new Thread(r, "workflow-agent-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
        return Executors.newCachedThreadPool(factory);
    }

    @Bean
    public TestRunner workflowTestRunner(WorkflowProperties properties) {
        WorkflowProperties.TestRunner cfg = properties.testRunner();
        return switch (cfg.mode()) {
            case WorkflowProperties.TestRunner.PLAN_VERIFICATION -> new PlanVerificationTestRunner();
            case WorkflowProperties.TestRunner.COMMAND -> new CommandTestRunner(cfg.command(), cfg.timeout());
            default -> throw new IllegalStateException("app.workflow.test-runner.mode must be '"
                    + WorkflowProperties.TestRunner.PLAN_VERIFICATION + "' or '" + WorkflowProperties.TestRunner.COMMAND + "', was '"
                    + cfg.mode() + "'");
        };
    }
}
