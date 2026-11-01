package com.schwab.urlshortener.agentic;

import com.schwab.urlshortener.agentic.model.ArtifactSource;
import com.schwab.urlshortener.agentic.model.SdlcStage;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * One stage's output within a run: the typed artifact as JSON (for tools)
 * and rendered Markdown (for people), plus provenance — which
 * {@link ArtifactSource} produced it, which model if any, how long it took,
 * and the tokens the API reported. Written once by the orchestrator and
 * never edited afterward; a reviewer's feedback goes on the run, not here.
 */
@Entity
@Table(
        name = "sdlc_artifact",
        uniqueConstraints = @UniqueConstraint(name = "uq_sdlc_artifact_run_stage", columnNames = {"runId", "stage"}),
        indexes = @Index(name = "idx_sdlc_artifact_run_id", columnList = "runId")
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SdlcArtifact {

    public enum Status {
        COMPLETED,
        FAILED,
        /** Not attempted because an earlier stage failed. */
        SKIPPED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long runId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SdlcStage stage;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private ArtifactSource source;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;

    @Column(length = 100)
    private String model;

    @Lob
    private String contentJson;

    @Lob
    private String contentMarkdown;

    private Long durationMs;

    private Long inputTokens;

    private Long outputTokens;

    @Column(length = 1000)
    private String errorMessage;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
