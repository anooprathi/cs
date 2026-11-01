package com.schwab.urlshortener.agentic;

import com.schwab.urlshortener.agentic.model.ExecutionMode;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * One execution of the agentic SDLC pipeline over one requirement. The
 * artifacts themselves live in {@link SdlcArtifact} (one row per stage).
 *
 * Lifecycle: RUNNING → AWAITING_REVIEW (every stage completed) or FAILED
 * (a stage failed; later stages recorded as SKIPPED). From AWAITING_REVIEW
 * a human moves it to APPROVED or CHANGES_REQUESTED exactly once — see
 * SdlcRunService.review. {@code @Version} makes two concurrent reviews of
 * the same run resolve to one winner and one 409, never both "succeeding."
 */
@Entity
@Table(name = "sdlc_run", indexes = @Index(name = "idx_sdlc_run_created_at", columnList = "createdAt"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SdlcRun {

    public enum Status {
        RUNNING,
        AWAITING_REVIEW,
        APPROVED,
        CHANGES_REQUESTED,
        FAILED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Version
    private Long version;

    @Column(nullable = false, length = 200)
    private String title;

    @Lob
    @Column(nullable = false)
    private String requirementText;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private ExecutionMode executionMode;

    /** Model id for LLM runs; null for DETERMINISTIC_DEMO runs (no model involved). */
    @Column(length = 100)
    private String model;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private Status status;

    /** Null until the REVIEW stage has run (i.e. null for FAILED runs). */
    private Boolean qualityGatePassed;

    private Integer blockerCount;

    private Integer warningCount;

    @Column(length = 1000)
    private String failureReason;

    @Column(length = 100)
    private String reviewer;

    @Column(length = 2000)
    private String reviewComment;

    private Instant reviewedAt;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    private Instant completedAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
