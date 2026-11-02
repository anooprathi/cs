package com.schwab.urlshortener.workflow.persistence;

import com.schwab.urlshortener.workflow.engine.ArtifactOrigin;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * One immutable version of a named artifact within a run (e.g.
 * {@code requirement-spec@v2}). Every execution writes a new version and nothing
 * is overwritten, so a version a reviewer approved can always be fetched again.
 *
 * Status is carried by flags, not deletions:
 *  - {@code accepted}: the producing task's exit gate passed. Only accepted
 *    versions are ever handed downstream or reused.
 *  - {@code invalidated}: an operator declared this output invalid. It is never
 *    reused, even if the inputs are unchanged.
 * Human decisions live in {@link WorkflowApproval}, bound to this row's id,
 * version, and hash.
 *
 * {@code resultJson} is the producing agent's structured result envelope:
 * summary, validation evidence, assumptions, risks, and decisions with
 * rationale. Together with lineage edges and {@code gatesJson}, it is the
 * decision lineage of this version.
 */
@Entity
@Table(
        name = "workflow_artifact",
        uniqueConstraints = @UniqueConstraint(name = "uq_workflow_artifact_version", columnNames = {"runId", "name", "version"}),
        indexes = {
                @Index(name = "idx_workflow_artifact_run_id", columnList = "runId"),
                @Index(name = "idx_workflow_artifact_reuse", columnList = "runId, name, inputFingerprint")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WorkflowArtifact {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long runId;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false)
    private int version;

    @Column(nullable = false, length = 100)
    private String taskKey;

    private int taskAttempt;

    @Column(nullable = false, length = 100)
    private String agentKey;

    @Column(length = 40)
    private String agentVersion;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private ArtifactOrigin origin;

    /** Which execution adapter produced it (deterministic-demo / anthropic-llm / deterministic code). */
    @Column(length = 40)
    private String adapter;

    @Column(length = 100)
    private String model;

    /** Produced by the defined fallback path after an LLM failure. */
    private boolean fallback;

    @Column(nullable = false, length = 100)
    private String contentType;

    @Lob
    @Column(nullable = false)
    private String contentJson;

    @Lob
    private String contentMarkdown;

    @Column(nullable = false, length = 64)
    private String contentHash;

    @Column(nullable = false, length = 64)
    private String inputFingerprint;

    @Lob
    private String resultJson;

    @Lob
    private String gatesJson;

    private boolean accepted;

    private boolean invalidated;

    private Long inputTokens;

    private Long outputTokens;

    private Double estimatedCostUsd;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
