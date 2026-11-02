package com.schwab.urlshortener.workflow.engine;

import java.util.EnumSet;
import java.util.Set;

/**
 * Lifecycle of one task within a run.
 *
 * <pre>
 * PENDING ──(all deps SUCCEEDED)──► READY ──► RUNNING ──┬─(gates pass)──────────────► SUCCEEDED
 *    ▲                                ▲                 ├─(gates pass, approval node)► WAITING_APPROVAL ─► SUCCEEDED | FAILED
 *    │                                │                 └─(gate or executor fails)──► FAILED
 *    │                                └─(reusable prior output: same input fingerprint) ─► SUCCEEDED, no re-execution
 *    ├── resume: FAILED / SKIPPED / CANCELLED / interrupted RUNNING
 * SKIPPED   ◄── an upstream task FAILED, SKIPPED, or was CANCELLED
 * INVALIDATED ◄── its output was invalidated, or an upstream output changed; re-enters scheduling like PENDING
 * CANCELLED ◄── the run was cancelled before the task finished
 * </pre>
 */
public enum TaskState {
    PENDING,
    READY,
    RUNNING,
    WAITING_APPROVAL,
    SUCCEEDED,
    FAILED,
    SKIPPED,
    INVALIDATED,
    CANCELLED;

    /** States from which a task can still be scheduled once its dependencies succeed. */
    public static final Set<TaskState> SCHEDULABLE = EnumSet.of(PENDING, INVALIDATED, READY);

    /** States that block every downstream task from running in this drive. */
    public static final Set<TaskState> BLOCKING = EnumSet.of(FAILED, SKIPPED, CANCELLED);

    /** States a resume puts back to PENDING. RUNNING here means the previous process died mid-task. */
    public static final Set<TaskState> RESET_ON_RESUME = EnumSet.of(FAILED, SKIPPED, CANCELLED, RUNNING, READY);
}
