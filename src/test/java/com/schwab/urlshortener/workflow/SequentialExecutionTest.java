package com.schwab.urlshortener.workflow;

import com.schwab.urlshortener.agentic.model.ExecutionMode;
import com.schwab.urlshortener.workflow.api.WorkflowRequests;
import com.schwab.urlshortener.workflow.api.WorkflowViews;
import com.schwab.urlshortener.workflow.engine.RunStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import static com.schwab.urlshortener.workflow.FakeAgents.SCRIPT;
import static org.assertj.core.api.Assertions.assertThat;

/** With max-parallelism=1 the same diamond runs strictly sequentially, in topological order. */
@SpringBootTest
@ActiveProfiles("test")
@Import(FakeAgents.Config.class)
@TestPropertySource(properties = {"app.workflow.workspace.enabled=false", "app.workflow.max-parallelism=1"})
class SequentialExecutionTest {

    @Autowired
    WorkflowService service;

    @Test
    void maxParallelismOne_runsOneTaskAtATime_inTopologicalOrder() {
        SCRIPT.reset();
        SCRIPT.behavior.put("left", req -> {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return FakeAgents.ok("left", "left-out");
        });

        WorkflowViews.Run run = service.start(service.create(new WorkflowRequests.CreateRun("sequential", "Tenants must get a result.",
                ExecutionMode.DETERMINISTIC_DEMO, FakeAgents.diamond(false), null)).id());

        assertThat(run.status()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(SCRIPT.maxConcurrent.get()).isEqualTo(1);
        assertThat(SCRIPT.starts).containsExactly("root", "left", "right", "join");
    }
}
