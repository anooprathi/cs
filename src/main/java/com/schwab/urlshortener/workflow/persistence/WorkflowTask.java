package com.schwab.urlshortener.workflow.persistence;

import com.schwab.urlshortener.workflow.engine.TaskState;
import com.schwab.urlshortener.workflow.graph.WorkflowStage;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * Persistent state of one graph node within a run. Every transition is written
 * as it happens, and WorkflowStore appends an audit event for each one, so the
 * table is an accurate picture of the run at any moment, including after a crash.
 *
 * {@code outputRefsJson} maps artifact name → artifact id of this task's CURRENT
 * accepted output: the exact versions downstream tasks consume and approvals
 * bind to. {@code fingerprint} is the input fingerprint that output was produced
 * from. Resume compares it against a freshly computed one to decide whether the
 * work is still valid.
 */
@Entity
@Table(
        name = "workflow_task",
        uniqueConstraints = @UniqueConstraint(name = "uq_workflow_task_run_key", columnNames = {"runId", "taskKey"}),
        indexes = @Index(name = "idx_workflow_task_run_id", columnList = "runId")
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WorkflowTask {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Version
    private Long version;

    @Column(nullable = false)
    private Long runId;

    @Column(nullable = false, length = 100)
    private String taskKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private WorkflowStage stage;

    @Column(nullable = false, length = 100)
    private String agentKey;

    @Column(length = 40)
    private String agentVersion;

    /** Approval required by the graph or by policy (app.workflow.policy.approval-required-tasks). */
    private boolean requiresApproval;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private TaskState state;

    /** How many times the agent has actually been invoked (all drives, including retries). A reused output does not count. */
    private int attempts;

    /** Automatic retries after transient failures, all drives. */
    private int retries;

    /** True if the current SUCCEEDED state came from reusing a prior output rather than executing. */
    private boolean reused;

    /** True if the current output came from the defined fallback path. */
    private boolean fallbackUsed;

    /** Why the current output needs human approval (graph/policy rule, ambiguities, fallback, protected paths). */
    @Column(length = 1000)
    private String reviewReason;

    @Column(length = 64)
    private String fingerprint;

    @Lob
    private String outputRefsJson;

    @Lob
    private String entryGateJson;

    @Lob
    private String exitGateJson;

    @Column(length = 1000)
    private String errorMessage;

    private Instant startedAt;

    private Instant finishedAt;
}
