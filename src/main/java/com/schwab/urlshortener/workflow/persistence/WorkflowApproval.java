package com.schwab.urlshortener.workflow.persistence;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * One human decision on ONE exact artifact version, identified by id, version
 * number, AND content hash. An approval of {@code readiness-report@v1} says
 * nothing about v2.
 *
 * Lifecycle: VALID when recorded, then STALE (with a reason and timestamp) as
 * soon as the approved version stops being the task's current output. That
 * happens when the task re-runs and produces a different version, when its
 * output is invalidated, or when an upstream change re-plans it. A STALE
 * approval is never deleted. It stays as audit evidence of what was approved
 * when, and the engine requires a fresh decision on the new version.
 */
@Entity
@Table(name = "workflow_approval", indexes = {
        @Index(name = "idx_workflow_approval_run_task", columnList = "runId, taskKey"),
        @Index(name = "idx_workflow_approval_artifact", columnList = "artifactId")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WorkflowApproval {

    public enum Decision {
        APPROVED,
        REJECTED
    }

    public enum Status {
        VALID,
        STALE
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Version
    private Long version;

    @Column(nullable = false)
    private Long runId;

    @Column(nullable = false, length = 100)
    private String taskKey;

    @Column(nullable = false)
    private Long artifactId;

    @Column(nullable = false, length = 100)
    private String artifactName;

    private int artifactVersion;

    @Column(nullable = false, length = 64)
    private String artifactHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Decision decision;

    @Column(nullable = false, length = 100)
    private String reviewer;

    @Column(length = 2000)
    private String comment;

    @Column(nullable = false)
    private Instant decidedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;

    @Column(length = 500)
    private String staleReason;

    private Instant staledAt;
}
