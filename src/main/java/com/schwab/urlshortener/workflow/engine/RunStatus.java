package com.schwab.urlshortener.workflow.engine;

/** Lifecycle of a workflow run, derived from its tasks' states at the end of each drive. */
public enum RunStatus {
    /** Submitted, tasks PENDING, never started. */
    CREATED,
    /** A driver is executing tasks right now. */
    RUNNING,
    /** No task can proceed until a human decides on a WAITING_APPROVAL task. */
    WAITING_APPROVAL,
    /** Every task SUCCEEDED. */
    SUCCEEDED,
    /** At least one task FAILED; resumable. */
    FAILED,
    /** Cancelled by an operator: unfinished tasks CANCELLED, workspace rolled back. Resumable. */
    CANCELLED,
    /** Safe-stopped by an operator: in-flight tasks finished, nothing new started, all work kept. Resumable. */
    STOPPED,
    /** Found RUNNING at application startup, so the process that was driving it died; resumable. */
    INTERRUPTED,
    /** Work was invalidated or the requirement changed since the last drive; resumable. */
    PAUSED
}
