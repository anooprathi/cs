package com.schwab.urlshortener.workflow.api;

import com.schwab.urlshortener.agentic.model.ExecutionMode;
import com.schwab.urlshortener.workflow.graph.WorkflowDefinition;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.Map;

/** Request bodies of the workflow API. */
public final class WorkflowRequests {

    private WorkflowRequests() {
    }

    /**
     * Submit a requirement. {@code definition} is optional (default: the
     * standard 7-stage graph). {@code demoFaults} (task key → number of attempts
     * that fail with an injected TRANSIENT fault) is accepted only for
     * DETERMINISTIC_DEMO runs, to demonstrate retry and recovery. It's recorded
     * on the run and in the audit trail.
     */
    public record CreateRun(
            @NotBlank @Size(max = 200) String title,
            @NotBlank @Size(max = 10_000) String requirement,
            ExecutionMode mode,
            WorkflowDefinition definition,
            Map<String, Integer> demoFaults
    ) {
    }

    /**
     * A human decision on a WAITING_APPROVAL task. {@code artifactVersions} must
     * name the exact version of every output the reviewer looked at
     * (name → version). If any differs from the task's current output, the
     * decision is refused (409): you can't approve a version you didn't see.
     */
    public record Decision(
            @NotNull Verdict decision,
            @NotBlank @Size(max = 100) String reviewer,
            @Size(max = 2000) String comment,
            @NotNull Map<String, Integer> artifactVersions
    ) {
        public enum Verdict {
            APPROVED,
            REJECTED
        }
    }

    public record AmendRequirement(@NotBlank @Size(max = 10_000) String requirement, @NotBlank @Size(max = 500) String reason,
                                   @NotBlank @Size(max = 100) String actor) {
    }

    public record Invalidate(@NotBlank @Size(max = 500) String reason, @NotBlank @Size(max = 100) String actor) {
    }

    public record Actor(@NotBlank @Size(max = 100) String actor) {
    }

    /**
     * Promote the approved patch to a local branch in the orchestrator's sandbox
     * clone. This is the high-impact action, so it requires the release approval to
     * be VALID for the exact patch version, {@code patchVersion} to match it, and
     * {@code confirm=true}. Never pushes, never touches the source repository.
     */
    public record Promote(@NotBlank @Size(max = 100) String reviewer, @Size(max = 2000) String comment,
                          @NotNull Integer patchVersion, boolean confirm) {
        @AssertTrue(message = "confirm must be true to promote")
        public boolean isConfirmed() {
            return confirm;
        }
    }
}
