package com.schwab.urlshortener.workflow.api;

import com.schwab.urlshortener.agentic.model.ExecutionMode;
import com.schwab.urlshortener.config.OpenApiConfig;
import com.schwab.urlshortener.dto.PageResponse;
import com.schwab.urlshortener.workflow.WorkflowReports;
import com.schwab.urlshortener.workflow.WorkflowService;
import com.schwab.urlshortener.workflow.audit.AuditLog;
import com.schwab.urlshortener.workflow.graph.WorkflowDefinition;
import com.schwab.urlshortener.workflow.metrics.WorkflowMetricsService;
import com.schwab.urlshortener.workflow.scenario.ScenarioService;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;

/**
 * SDLC workflow orchestration API: submit a requirement, run it through the
 * explicit 7-stage dependency graph, decide human checkpoints, and inspect
 * status, artifacts, lineage, history, timeline, and metrics.
 *
 * Operator-only (X-Admin-Key, ROLE_ADMIN). Start, resume, and decisions return
 * 202 Accepted when execution is asynchronous (the default). Poll
 * GET /runs/{id}, or read /timeline.
 */
@RestController
@RequestMapping("/api/v1/workflows")
@SecurityRequirement(name = OpenApiConfig.ADMIN_KEY_SCHEME)
public class WorkflowController {

    private static final MediaType MARKDOWN = MediaType.parseMediaType("text/markdown;charset=UTF-8");

    private final WorkflowService service;
    private final WorkflowReports reports;
    private final WorkflowMetricsService metrics;
    private final ScenarioService scenarios;

    public WorkflowController(WorkflowService service, WorkflowReports reports, WorkflowMetricsService metrics, ScenarioService scenarios) {
        this.service = service;
        this.reports = reports;
        this.metrics = metrics;
        this.scenarios = scenarios;
    }

    @GetMapping("/capabilities")
    public WorkflowViews.Capabilities capabilities() {
        return service.capabilities();
    }

    @GetMapping("/definitions/standard")
    public WorkflowDefinition standardDefinition() {
        return service.standardDefinition();
    }

    @PostMapping("/definitions/validate")
    public WorkflowViews.DefinitionValidation validate(@RequestBody WorkflowDefinition definition) {
        return service.validate(definition);
    }

    @PostMapping("/runs")
    public ResponseEntity<WorkflowViews.Run> create(@Valid @RequestBody WorkflowRequests.CreateRun request) {
        WorkflowViews.Run run = service.create(request);
        return ResponseEntity.created(URI.create("/api/v1/workflows/runs/" + run.id())).body(run);
    }

    @GetMapping("/runs")
    public PageResponse<WorkflowViews.RunSummary> list(@PageableDefault(size = 20) Pageable pageable) {
        return service.list(pageable);
    }

    @GetMapping("/runs/{runId}")
    public WorkflowViews.Run get(@PathVariable Long runId) {
        return service.view(runId);
    }

    @PostMapping("/runs/{runId}/start")
    public ResponseEntity<WorkflowViews.Run> start(@PathVariable Long runId) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(service.start(runId));
    }

    @PostMapping("/runs/{runId}/resume")
    public ResponseEntity<WorkflowViews.Run> resume(@PathVariable Long runId) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(service.resume(runId));
    }

    /** Safe-stop: finish in-flight tasks, start nothing new, keep all work. */
    @PostMapping("/runs/{runId}/stop")
    public ResponseEntity<WorkflowViews.Run> stop(@PathVariable Long runId, @Valid @RequestBody WorkflowRequests.Actor actor) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(service.stop(runId, actor.actor()));
    }

    @PostMapping("/runs/{runId}/cancel")
    public ResponseEntity<WorkflowViews.Run> cancel(@PathVariable Long runId, @Valid @RequestBody WorkflowRequests.Actor actor) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(service.cancel(runId, actor.actor()));
    }

    @PostMapping("/runs/{runId}/tasks/{taskKey}/decision")
    public ResponseEntity<WorkflowViews.Run> decide(@PathVariable Long runId, @PathVariable String taskKey,
                                                    @Valid @RequestBody WorkflowRequests.Decision decision) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(service.decide(runId, taskKey, decision));
    }

    @PostMapping("/runs/{runId}/tasks/{taskKey}/invalidate")
    public WorkflowViews.Run invalidate(@PathVariable Long runId, @PathVariable String taskKey,
                                        @Valid @RequestBody WorkflowRequests.Invalidate request) {
        return service.invalidate(runId, taskKey, request);
    }

    @PutMapping("/runs/{runId}/requirement")
    public WorkflowViews.Run amendRequirement(@PathVariable Long runId, @Valid @RequestBody WorkflowRequests.AmendRequirement request) {
        return service.amendRequirement(runId, request);
    }

    @PostMapping("/runs/{runId}/workspace/rollback")
    public WorkflowViews.Run rollback(@PathVariable Long runId, @Valid @RequestBody WorkflowRequests.Actor actor) {
        return service.rollbackWorkspace(runId, actor.actor());
    }

    @PostMapping("/runs/{runId}/promotion")
    public WorkflowViews.Run promote(@PathVariable Long runId, @Valid @RequestBody WorkflowRequests.Promote request) {
        return service.promote(runId, request);
    }

    @PostMapping("/runs/{runId}/promotion/compensate")
    public WorkflowViews.Run compensatePromotion(@PathVariable Long runId, @Valid @RequestBody WorkflowRequests.Actor actor) {
        return service.compensatePromotion(runId, actor.actor());
    }

    @GetMapping("/runs/{runId}/artifacts")
    public List<WorkflowViews.ArtifactSummary> artifacts(@PathVariable Long runId) {
        return service.artifacts(runId);
    }

    /** The current version by default, or {@code ?version=N}. */
    @GetMapping("/runs/{runId}/artifacts/{name}")
    public WorkflowViews.Artifact artifact(@PathVariable Long runId, @PathVariable String name, @RequestParam(required = false) Integer version) {
        return service.artifact(runId, name, version);
    }

    @GetMapping("/runs/{runId}/artifacts/{name}/lineage")
    public WorkflowViews.Lineage lineage(@PathVariable Long runId, @PathVariable String name, @RequestParam(required = false) Integer version) {
        return service.lineage(runId, name, version);
    }

    @GetMapping("/runs/{runId}/approvals")
    public List<WorkflowViews.Approval> approvals(@PathVariable Long runId) {
        return service.approvals(runId);
    }

    @GetMapping("/runs/{runId}/history")
    public List<WorkflowViews.Event> history(@PathVariable Long runId) {
        return service.history(runId);
    }

    @GetMapping(value = "/runs/{runId}/timeline", produces = "text/markdown")
    public ResponseEntity<String> timeline(@PathVariable Long runId) {
        return ResponseEntity.ok().contentType(MARKDOWN).body(reports.timeline(runId));
    }

    @GetMapping(value = "/runs/{runId}/report.md", produces = "text/markdown")
    public ResponseEntity<String> report(@PathVariable Long runId) {
        return ResponseEntity.ok().contentType(MARKDOWN).body(reports.report(runId));
    }

    @GetMapping("/runs/{runId}/audit/verify")
    public AuditLog.Verification verify(@PathVariable Long runId) {
        return service.verifyAudit(runId);
    }

    @GetMapping("/runs/{runId}/metrics")
    public WorkflowMetricsService.Metrics runMetrics(@PathVariable Long runId) {
        service.view(runId);
        return metrics.forRun(runId);
    }

    @GetMapping("/metrics")
    public WorkflowMetricsService.Metrics allMetrics() {
        return metrics.all();
    }

    /** Runs one end-to-end scenario synchronously and returns its report as Markdown. */
    @PostMapping(value = "/scenarios/{name}", produces = "text/markdown")
    public ResponseEntity<String> scenario(@PathVariable String name, @RequestParam(defaultValue = "DETERMINISTIC_DEMO") ExecutionMode mode) {
        return ResponseEntity.ok().contentType(MARKDOWN).body(scenarios.run(name, mode).markdown());
    }
}
