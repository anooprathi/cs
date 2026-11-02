package com.schwab.urlshortener.workflow.scenario;

import com.schwab.urlshortener.agentic.model.ExecutionMode;
import com.schwab.urlshortener.workflow.engine.RunStatus;
import com.schwab.urlshortener.workflow.workspace.WorkspaceManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The three demonstration scenarios, end to end, with the REAL stage agents in
 * DETERMINISTIC_DEMO mode and REAL git workspaces cloned from this repository.
 * Every evidence check each scenario computes from its audit log must pass.
 * Skipped only if the working directory isn't a git checkout (e.g. a source zip).
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {"app.workflow.workspace.enabled=true", "app.workflow.workspace.source-repository=.",
        "app.workflow.demo.simulated-latency=PT0.05S"})
class ScenarioEndToEndTest {

    @Autowired
    ScenarioService scenarios;

    @Autowired
    WorkspaceManager workspaces;

    @BeforeEach
    void requireGit() {
        assumeTrue(workspaces.unavailableReason().isEmpty(), () -> "workspace unavailable: " + workspaces.unavailableReason().orElse(""));
    }

    private void assertAllChecksPass(ScenarioService.Report report) {
        assertThat(report.checks())
                .as(report.markdown())
                .allSatisfy(c -> assertThat(c.passed()).as(c.name() + ": " + c.detail()).isTrue());
        assertThat(report.finalStatus()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(report.markdown()).contains("Execution mode: DETERMINISTIC_DEMO").contains("scripted");
    }

    @Test
    void greenfield_parallelism_recoverableFailure_approval_promotion_andPreservingReplan() {
        ScenarioService.Report report = scenarios.run("greenfield", ExecutionMode.DETERMINISTIC_DEMO);

        assertAllChecksPass(report);
        assertThat(report.steps()).anyMatch(s -> s.outcome().contains("Local branch workflow/run-"));
    }

    @Test
    void brownfield_impactAnalysis_rejectedPolicyGate_andReplanAfterAmendment() {
        ScenarioService.Report report = scenarios.run("brownfield", ExecutionMode.DETERMINISTIC_DEMO);

        assertAllChecksPass(report);
        assertThat(report.summaryMarkdown()).contains("EXIT_GATE_FAILED").contains("REQUIREMENT_UPDATED");
    }

    @Test
    void ambiguous_clarificationHoldsExecution_untilAHumanResolvesIt() {
        ScenarioService.Report report = scenarios.run("ambiguous", ExecutionMode.DETERMINISTIC_DEMO);

        assertAllChecksPass(report);
        assertThat(report.summaryMarkdown()).contains("REJECTED").contains("APPROVAL_STALE");
    }
}
