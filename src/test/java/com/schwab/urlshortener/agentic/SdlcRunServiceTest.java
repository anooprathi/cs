package com.schwab.urlshortener.agentic;

import com.schwab.urlshortener.agentic.api.SdlcReviewRequest;
import com.schwab.urlshortener.agentic.model.ArtifactSource;
import com.schwab.urlshortener.agentic.model.ExecutionMode;
import com.schwab.urlshortener.agentic.model.SdlcStage;
import com.schwab.urlshortener.agentic.review.MarkdownRenderer;
import com.schwab.urlshortener.exception.InvalidSdlcRunStateException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Service-level paths the HTTP integration tests can't easily reach: a
 * FAILED run's report and review, and two reviewers racing on one run.
 */
class SdlcRunServiceTest {

    private SdlcRunRepository runRepository;
    private SdlcArtifactRepository artifactRepository;
    private SdlcRunService service;

    @BeforeEach
    void setUp() {
        runRepository = mock(SdlcRunRepository.class);
        artifactRepository = mock(SdlcArtifactRepository.class);
        service = new SdlcRunService(mock(SdlcOrchestrator.class), runRepository, artifactRepository,
                AgenticFixtures.catalog(), new MarkdownRenderer(), AgenticFixtures.MAPPER);
    }

    private SdlcRun failedLlmRun() {
        SdlcRun run = SdlcRun.builder().id(3L).title("Tags | labels").requirementText("line one\nline two")
                .executionMode(ExecutionMode.LLM).model("claude-opus-5").status(SdlcRun.Status.FAILED)
                .failureReason("PLAN stage failed: Model declined to produce ImplementationPlan")
                .createdAt(Instant.parse("2026-11-01T10:00:00Z")).completedAt(Instant.parse("2026-11-01T10:01:00Z"))
                .build();
        when(runRepository.findById(3L)).thenReturn(Optional.of(run));
        when(artifactRepository.findByRunIdOrderByIdAsc(3L)).thenReturn(List.of(
                SdlcArtifact.builder().runId(3L).stage(SdlcStage.REQUIREMENTS).source(ArtifactSource.LLM)
                        .status(SdlcArtifact.Status.COMPLETED).contentMarkdown("## 1. Requirements\n\n(ok)\n\n").build(),
                SdlcArtifact.builder().runId(3L).stage(SdlcStage.PLAN).source(ArtifactSource.LLM)
                        .status(SdlcArtifact.Status.FAILED).errorMessage("Model declined | refusal").build(),
                SdlcArtifact.builder().runId(3L).stage(SdlcStage.REVIEW).source(ArtifactSource.DETERMINISTIC_GUARDRAIL)
                        .status(SdlcArtifact.Status.SKIPPED).errorMessage("Not run: an earlier stage failed.").build()));
        return run;
    }

    @Test
    void report_ofFailedLlmRun_showsBannerFailureAndSkippedStages() {
        failedLlmRun();

        String md = service.report(3L);

        assertThat(md).startsWith("# SDLC run 3: Tags \\| labels")
                .contains("**Execution mode: LLM (`claude-opus-5`).**")
                .contains("| Quality gate | not run |")
                .contains("**Run failed:** PLAN stage failed")
                .contains("> line one\n> line two")
                .contains("## PLAN: FAILED\n\nModel declined \\| refusal")
                .contains("## REVIEW: SKIPPED")
                .contains("_Not reviewable: the run did not complete._");
    }

    @Test
    void review_ofFailedRun_isConflict() {
        failedLlmRun();

        assertThatThrownBy(() -> service.review(3L, new SdlcReviewRequest(SdlcReviewRequest.Decision.CHANGES_REQUESTED, "r", "c")))
                .isInstanceOf(InvalidSdlcRunStateException.class)
                .hasMessageContaining("is FAILED");
    }

    @Test
    void concurrentReview_losingWriterGetsConflict_notA500() {
        SdlcRun run = SdlcRun.builder().id(4L).title("t").requirementText("r").executionMode(ExecutionMode.DETERMINISTIC_DEMO)
                .status(SdlcRun.Status.AWAITING_REVIEW).qualityGatePassed(true).blockerCount(0).build();
        when(runRepository.findById(4L)).thenReturn(Optional.of(run));
        when(runRepository.saveAndFlush(any())).thenThrow(new ObjectOptimisticLockingFailureException(SdlcRun.class, 4L));

        assertThatThrownBy(() -> service.review(4L, new SdlcReviewRequest(SdlcReviewRequest.Decision.APPROVED, "r", null)))
                .isInstanceOf(InvalidSdlcRunStateException.class)
                .hasMessageContaining("reviewed concurrently");
    }

    @Test
    void artifact_ofSkippedStage_hasNoContent() {
        failedLlmRun();
        when(artifactRepository.findByRunIdAndStage(3L, SdlcStage.REVIEW)).thenReturn(Optional.of(
                SdlcArtifact.builder().runId(3L).stage(SdlcStage.REVIEW).source(ArtifactSource.DETERMINISTIC_GUARDRAIL)
                        .status(SdlcArtifact.Status.SKIPPED).build()));

        assertThat(service.getArtifact(3L, SdlcStage.REVIEW).content()).isNull();
        assertThat(service.getArtifact(3L, SdlcStage.REVIEW).status()).isEqualTo(SdlcArtifact.Status.SKIPPED);
    }
}
