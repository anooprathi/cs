package com.schwab.urlshortener.agentic;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.schwab.urlshortener.agentic.api.AgenticCapabilitiesResponse;
import com.schwab.urlshortener.agentic.api.CreateSdlcRunRequest;
import com.schwab.urlshortener.agentic.api.SdlcArtifactResponse;
import com.schwab.urlshortener.agentic.api.SdlcReviewRequest;
import com.schwab.urlshortener.agentic.api.SdlcRunResponse;
import com.schwab.urlshortener.agentic.api.SdlcRunSummaryResponse;
import com.schwab.urlshortener.agentic.model.ExecutionMode;
import com.schwab.urlshortener.agentic.model.SdlcStage;
import com.schwab.urlshortener.agentic.review.MarkdownRenderer;
import com.schwab.urlshortener.dto.PageResponse;
import com.schwab.urlshortener.exception.InvalidSdlcRunStateException;
import com.schwab.urlshortener.exception.SdlcRunNotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Application service over SDLC runs: start, query, render, review. The
 * orchestrator produces runs; this class is what the controller talks to,
 * and it owns the one piece of human-facing state: the review decision.
 *
 * Review rules (the human-in-the-loop contract):
 *  - Only a run in AWAITING_REVIEW can be reviewed, exactly once. A FAILED
 *    run has nothing complete to approve, and a decided run stays decided:
 *    a new requirement version is a new run, not an edit of an approved one.
 *  - APPROVED is refused while the quality gate has blockers. The gate
 *    finding something structurally broken (an untested criterion, a
 *    nonexistent component) isn't something a reviewer can wave through.
 *  - CHANGES_REQUESTED requires a comment, because a rejection with no
 *    reason isn't actionable.
 *
 * Approval records a decision. It does not apply anything to the codebase.
 */
@Service
@Slf4j
public class SdlcRunService {

    private final SdlcOrchestrator orchestrator;
    private final SdlcRunRepository runRepository;
    private final SdlcArtifactRepository artifactRepository;
    private final ComponentCatalog catalog;
    private final MarkdownRenderer renderer;
    private final ObjectMapper objectMapper;

    public SdlcRunService(SdlcOrchestrator orchestrator, SdlcRunRepository runRepository,
                          SdlcArtifactRepository artifactRepository, ComponentCatalog catalog,
                          MarkdownRenderer renderer, ObjectMapper objectMapper) {
        this.orchestrator = orchestrator;
        this.runRepository = runRepository;
        this.artifactRepository = artifactRepository;
        this.catalog = catalog;
        this.renderer = renderer;
        this.objectMapper = objectMapper;
    }

    public AgenticCapabilitiesResponse capabilities() {
        List<ExecutionMode> modes = orchestrator.llmAvailable()
                ? List.of(ExecutionMode.DETERMINISTIC_DEMO, ExecutionMode.LLM)
                : List.of(ExecutionMode.DETERMINISTIC_DEMO);
        String notice = orchestrator.llmAvailable()
                ? "LLM mode is enabled: runs with mode=LLM send the requirement text to the Anthropic API ("
                + orchestrator.llmModel() + ")."
                : "LLM mode is disabled on this deployment. Only DETERMINISTIC_DEMO (rule-based, no model) is available.";
        return new AgenticCapabilitiesResponse(orchestrator.defaultMode(), modes, orchestrator.llmAvailable(),
                orchestrator.llmModel(), catalog.size(), orchestrator.stages(), notice);
    }

    public SdlcRunResponse start(CreateSdlcRunRequest request) {
        SdlcRun run = orchestrator.execute(request.title().strip(), request.requirement().strip(), request.mode());
        return toResponse(run);
    }

    @Transactional(readOnly = true)
    public SdlcRunResponse getRun(Long runId) {
        return toResponse(findRun(runId));
    }

    @Transactional(readOnly = true)
    public PageResponse<SdlcRunSummaryResponse> list(Pageable pageable) {
        Pageable effective = pageable.getSort().isSorted() ? pageable
                : PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), Sort.by(Sort.Direction.DESC, "createdAt"));
        return PageResponse.from(runRepository.findAll(effective).map(SdlcRunSummaryResponse::from));
    }

    @Transactional(readOnly = true)
    public SdlcArtifactResponse getArtifact(Long runId, SdlcStage stage) {
        findRun(runId);
        SdlcArtifact a = artifactRepository.findByRunIdAndStage(runId, stage)
                .orElseThrow(() -> new SdlcRunNotFoundException(runId, stage.name()));
        return new SdlcArtifactResponse(runId, a.getStage(), a.getSource(), a.getStatus(), a.getModel(),
                a.getDurationMs(), a.getInputTokens(), a.getOutputTokens(), a.getErrorMessage(),
                parse(a.getContentJson()), a.getContentMarkdown(), a.getCreatedAt());
    }

    /** All artifacts of a run as one Markdown document, banner first, review decision last. */
    @Transactional(readOnly = true)
    public String report(Long runId) {
        SdlcRun run = findRun(runId);
        List<SdlcArtifact> artifacts = artifactRepository.findByRunIdOrderByIdAsc(runId);

        StringBuilder sb = new StringBuilder();
        sb.append("# SDLC run ").append(run.getId()).append(": ").append(renderer.cell(run.getTitle())).append("\n\n");
        sb.append(renderer.banner(run.getExecutionMode(), run.getModel())).append('\n');
        sb.append("| Field | Value |\n|---|---|\n")
                .append("| Status | ").append(run.getStatus()).append(" |\n")
                .append("| Quality gate | ").append(run.getQualityGatePassed() == null ? "not run"
                        : run.getQualityGatePassed() ? "PASSED" : "FAILED").append(" |\n")
                .append("| Created | ").append(run.getCreatedAt()).append(" |\n")
                .append("| Completed | ").append(run.getCompletedAt() == null ? "-" : run.getCompletedAt()).append(" |\n\n");
        if (run.getFailureReason() != null) {
            sb.append("**Run failed:** ").append(renderer.cell(run.getFailureReason())).append("\n\n");
        }
        sb.append("## Original requirement\n\n");
        for (String line : run.getRequirementText().split("\\R")) {
            sb.append("> ").append(line).append('\n');
        }
        sb.append('\n');

        for (SdlcArtifact a : artifacts) {
            if (a.getStatus() == SdlcArtifact.Status.COMPLETED) {
                sb.append(a.getContentMarkdown());
            } else {
                sb.append("## ").append(a.getStage()).append(": ").append(a.getStatus()).append("\n\n")
                        .append(renderer.cell(a.getErrorMessage())).append("\n\n");
            }
        }

        sb.append("## Human review\n\n");
        if (run.getReviewedAt() == null) {
            sb.append(run.getStatus() == SdlcRun.Status.AWAITING_REVIEW
                    ? "_Awaiting review. Nothing in this document has been applied to the codebase._\n"
                    : "_Not reviewable: the run did not complete._\n");
        } else {
            sb.append("**").append(run.getStatus()).append("** by ").append(renderer.cell(run.getReviewer()))
                    .append(" at ").append(run.getReviewedAt()).append(".\n\n");
            if (run.getReviewComment() != null && !run.getReviewComment().isBlank()) {
                sb.append("> ").append(renderer.cell(run.getReviewComment())).append('\n');
            }
        }
        return sb.toString();
    }

    @Transactional
    public SdlcRunResponse review(Long runId, SdlcReviewRequest request) {
        SdlcRun run = findRun(runId);
        if (run.getStatus() != SdlcRun.Status.AWAITING_REVIEW) {
            throw new InvalidSdlcRunStateException("Run " + runId + " is " + run.getStatus()
                    + "; only a run in AWAITING_REVIEW can be reviewed.");
        }
        if (request.decision() == SdlcReviewRequest.Decision.APPROVED && !Boolean.TRUE.equals(run.getQualityGatePassed())) {
            throw new InvalidSdlcRunStateException("Run " + runId + " cannot be approved: the quality gate reported "
                    + run.getBlockerCount() + " blocker(s). Request changes instead.");
        }
        if (request.decision() == SdlcReviewRequest.Decision.CHANGES_REQUESTED
                && (request.comment() == null || request.comment().isBlank())) {
            throw new IllegalArgumentException("A comment is required when requesting changes.");
        }

        run.setStatus(request.decision() == SdlcReviewRequest.Decision.APPROVED
                ? SdlcRun.Status.APPROVED : SdlcRun.Status.CHANGES_REQUESTED);
        run.setReviewer(request.reviewer().strip());
        run.setReviewComment(request.comment());
        run.setReviewedAt(Instant.now());
        try {
            run = runRepository.saveAndFlush(run);
        } catch (ObjectOptimisticLockingFailureException e) {
            throw new InvalidSdlcRunStateException("Run " + runId + " was reviewed concurrently by someone else; reload it.");
        }
        log.info("SDLC run {} reviewed: {} by {}", runId, run.getStatus(), run.getReviewer());
        return toResponse(run);
    }

    private SdlcRun findRun(Long runId) {
        return runRepository.findById(runId).orElseThrow(() -> new SdlcRunNotFoundException(runId));
    }

    private SdlcRunResponse toResponse(SdlcRun run) {
        return SdlcRunResponse.from(run, new ArrayList<>(artifactRepository.findByRunIdOrderByIdAsc(run.getId())));
    }

    private JsonNode parse(String json) {
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored artifact JSON is unreadable", e);
        }
    }
}
