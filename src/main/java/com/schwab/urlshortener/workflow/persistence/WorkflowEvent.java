package com.schwab.urlshortener.workflow.persistence;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * One append-only audit event. Written only through
 * {@link com.schwab.urlshortener.workflow.audit.AuditLog}, which redacts secrets
 * and chains each event's hash to the previous event of the same run.
 *
 * Append-only is enforced at the application level, not the database level:
 * every column is {@code updatable = false} and {@link #onUpdate()} throws, so
 * no JPA code path can modify a stored event. Database-level enforcement and
 * external anchoring of the hash chain are documented limitations (see
 * docs/governance.md "Audit integrity").
 */
@Entity
@Table(name = "workflow_event", indexes = {
        @Index(name = "idx_workflow_event_run_id", columnList = "runId"),
        @Index(name = "idx_workflow_event_type", columnList = "type")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WorkflowEvent {

    public enum Type {
        RUN_CREATED,
        RUN_STARTED,
        RUN_RESUMED,
        RUN_CONTINUED,
        RUN_DRIVE_ENDED,
        RUN_SUCCEEDED,
        RUN_FAILED,
        RUN_WAITING_APPROVAL,
        RUN_STOP_REQUESTED,
        RUN_STOPPED,
        RUN_CANCEL_REQUESTED,
        RUN_CANCELLED,
        RUN_INTERRUPTED,
        REQUIREMENT_UPDATED,
        TASK_TRANSITION,
        TASK_READY,
        TASK_STARTED,
        TASK_ATTEMPT_STARTED,
        TASK_ATTEMPT_FAILED,
        TASK_RETRY_SCHEDULED,
        TASK_RETRIES_EXHAUSTED,
        TASK_FALLBACK,
        TASK_REUSED,
        TASK_RESET,
        TASK_INTERRUPTED,
        TASK_INVALIDATED,
        TASK_SKIPPED,
        TASK_CANCELLED,
        TASK_SUCCEEDED,
        TASK_FAILED,
        ENTRY_GATE_PASSED,
        ENTRY_GATE_FAILED,
        EXIT_GATE_PASSED,
        EXIT_GATE_FAILED,
        ARTIFACT_STORED,
        AGENT_RESULT,
        APPROVAL_REQUESTED,
        APPROVED,
        REJECTED,
        APPROVAL_STALE,
        WORKSPACE_CREATED,
        WORKSPACE_PATCH_APPLIED,
        VALIDATION_COMMAND,
        WORKSPACE_ROLLED_BACK,
        WORKSPACE_DISCARDED,
        PATCH_PROMOTED,
        PROMOTION_COMPENSATED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, updatable = false)
    private Long runId;

    /** Position in the run's hash chain, starting at 1. */
    @Column(nullable = false, updatable = false)
    private long sequence;

    @Column(length = 100, updatable = false)
    private String taskKey;

    @Column(updatable = false)
    private Integer attempt;

    /** {@code agentKey@version} of the agent involved, if any. */
    @Column(length = 140, updatable = false)
    private String agentId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40, updatable = false)
    private Type type;

    @Column(length = 30, updatable = false)
    private String fromState;

    @Column(length = 30, updatable = false)
    private String toState;

    /** Who caused it: "engine", "system:startup", or a human reviewer/operator name. */
    @Column(length = 100, updatable = false)
    private String actor;

    @Column(length = 30, updatable = false)
    private String executionMode;

    @Column(length = 100, updatable = false)
    private String model;

    /** Versioned artifact refs involved (inputs and/or outputs), e.g. ["requirement-spec@v2#3f1a…"]. */
    @Column(length = 2000, updatable = false)
    private String refsJson;

    @Column(length = 2000, updatable = false)
    private String message;

    /** Structured details (gate outcomes, retry delay, approval binding, ...), redacted. */
    @Lob
    @Column(updatable = false)
    private String detailsJson;

    @Column(length = 64, updatable = false)
    private String prevHash;

    @Column(nullable = false, length = 64, updatable = false)
    private String hash;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    @PreUpdate
    void onUpdate() {
        throw new IllegalStateException("Audit events are append-only; event " + id + " cannot be modified");
    }
}
