package com.schwab.urlshortener.agentic.api;

import com.schwab.urlshortener.agentic.SdlcRunService;
import com.schwab.urlshortener.agentic.model.SdlcStage;
import com.schwab.urlshortener.config.OpenApiConfig;
import com.schwab.urlshortener.dto.PageResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;

/**
 * Agentic SDLC orchestration API: submit an engineering requirement and get
 * reviewable artifacts back (requirements → impact → plan → tests → risks →
 * quality gate), then record a human review decision.
 *
 * Operator-only (X-Admin-Key, ROLE_ADMIN — see SecurityConfig). This is an
 * internal engineering capability, not a tenant feature, so it isn't
 * tenant-scoped and a tenant's own key gets 403. Swagger's admin scheme
 * is applied the same way as AdminController.
 *
 * Runs execute synchronously: milliseconds in DETERMINISTIC_DEMO mode,
 * several sequential model calls (potentially minutes) in LLM mode. This is
 * an accepted prototype limitation (see README §12).
 */
@RestController
@RequestMapping("/api/v1/agentic")
@SecurityRequirement(name = OpenApiConfig.ADMIN_KEY_SCHEME)
public class AgenticController {

    public static final String MARKDOWN = "text/markdown";

    private final SdlcRunService runService;

    public AgenticController(SdlcRunService runService) {
        this.runService = runService;
    }

    /** Which execution modes this deployment can actually serve right now. */
    @GetMapping("/capabilities")
    public ResponseEntity<AgenticCapabilitiesResponse> capabilities() {
        return ResponseEntity.ok(runService.capabilities());
    }

    @PostMapping("/runs")
    public ResponseEntity<SdlcRunResponse> start(@Valid @RequestBody CreateSdlcRunRequest request) {
        SdlcRunResponse response = runService.start(request);
        return ResponseEntity.created(URI.create("/api/v1/agentic/runs/" + response.id())).body(response);
    }

    @GetMapping("/runs")
    public ResponseEntity<PageResponse<SdlcRunSummaryResponse>> list(@PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(runService.list(pageable));
    }

    @GetMapping("/runs/{runId}")
    public ResponseEntity<SdlcRunResponse> get(@PathVariable Long runId) {
        return ResponseEntity.ok(runService.getRun(runId));
    }

    /** {@code stage} is one of REQUIREMENTS, IMPACT, PLAN, TESTS, RISKS, REVIEW. */
    @GetMapping("/runs/{runId}/artifacts/{stage}")
    public ResponseEntity<SdlcArtifactResponse> artifact(@PathVariable Long runId, @PathVariable SdlcStage stage) {
        return ResponseEntity.ok(runService.getArtifact(runId, stage));
    }

    /** Every artifact of the run as one Markdown document, ready to paste into a ticket or PR. */
    @GetMapping(value = "/runs/{runId}/report.md", produces = MARKDOWN)
    public ResponseEntity<String> report(@PathVariable Long runId) {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(MARKDOWN + ";charset=UTF-8"))
                .body(runService.report(runId));
    }

    @PostMapping("/runs/{runId}/review")
    public ResponseEntity<SdlcRunResponse> review(@PathVariable Long runId, @Valid @RequestBody SdlcReviewRequest request) {
        return ResponseEntity.ok(runService.review(runId, request));
    }
}
