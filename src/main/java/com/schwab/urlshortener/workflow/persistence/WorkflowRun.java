package com.schwab.urlshortener.workflow.persistence;

import com.schwab.urlshortener.agentic.model.ExecutionMode;
import com.schwab.urlshortener.workflow.engine.RunStatus;
import com.schwab.urlshortener.workflow.engine.TestExecution;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * One workflow run: the requirement, the execution mode, and a JSON snapshot of
 * the graph it runs. Using a snapshot means resuming next week uses the same
 * graph as the original start, even if the default definition has changed since.
 *
 * Control flags: {@code stopRequested} is a safe-stop. The driver finishes
 * in-flight tasks, starts nothing new, and leaves the run resumable with all
 * work kept. {@code cancelRequested} does the same, then cancels remaining
 * tasks and rolls back the isolated workspace.
 *
 * Workspace and promotion fields record the run's only local side effects (a
 * worktree in the sandbox clone, and an optional local branch). They are what
 * rollback and compensation act on.
 */
@Entity
@Table(name = "workflow_run", indexes = @Index(name = "idx_workflow_run_created_at", columnList = "createdAt"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WorkflowRun {

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

    @Column(nullable = false, length = 64)
    private String requirementHash;

    /** Bumped on every requirement amendment, so audit events can say which revision a task saw. */
    private int requirementRevision;

    /**
     * The requirement revision the tasks were last validated (re-planned) against. Set when a START or RESUME drive
     * prepares the run. While it lags {@link #requirementRevision}, re-planning is pending: decisions and
     * promotion are refused, and any drive re-validates first.
     */
    private int plannedRequirementRevision;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private ExecutionMode executionMode;

    @Column(length = 100)
    private String model;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private TestExecution testExecution;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private RunStatus status;

    @Column(nullable = false, length = 100)
    private String definitionName;

    private int definitionVersion;

    @Lob
    @Column(nullable = false)
    private String definitionJson;

    /** DETERMINISTIC_DEMO only: task key → number of attempts that fail with an injected transient fault. */
    @Column(length = 1000)
    private String faultInjectionJson;

    private boolean cancelRequested;

    private boolean stopRequested;

    /** Number of times a driver has started on this run (start + resumes + continuations). */
    private int driveCount;

    @Column(length = 1000)
    private String failureReason;

    @Column(length = 64)
    private String baseCommit;

    @Column(length = 1000)
    private String workspacePath;

    @Column(length = 200)
    private String promotedBranch;

    @Column(length = 64)
    private String promotedCommit;

    @Column(length = 100)
    private String promotedBy;

    private Instant promotedAt;

    private Instant promotionCompensatedAt;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    private Instant startedAt;

    private Instant completedAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
