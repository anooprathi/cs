package com.schwab.urlshortener.workflow.audit;

import com.schwab.urlshortener.workflow.persistence.WorkflowEvent;

import java.util.List;
import java.util.Map;

/**
 * What a caller hands to {@link AuditLog#append}. Text fields are redacted by
 * the log, not by the caller. Never put prompt content or raw requirement text
 * here: use hashes and artifact refs instead.
 */
public record AuditEntry(
        Long runId,
        String taskKey,
        Integer attempt,
        String agentId,
        WorkflowEvent.Type type,
        String fromState,
        String toState,
        String actor,
        String executionMode,
        String model,
        List<String> refs,
        String message,
        Map<String, Object> details
) {

    public static Builder of(Long runId, WorkflowEvent.Type type) {
        return new Builder(runId, type);
    }

    public static final class Builder {
        private final Long runId;
        private final WorkflowEvent.Type type;
        private String taskKey;
        private Integer attempt;
        private String agentId;
        private String fromState;
        private String toState;
        private String actor = "engine";
        private String executionMode;
        private String model;
        private List<String> refs = List.of();
        private String message;
        private Map<String, Object> details = Map.of();

        private Builder(Long runId, WorkflowEvent.Type type) {
            this.runId = runId;
            this.type = type;
        }

        public Builder task(String taskKey, Integer attempt) {
            this.taskKey = taskKey;
            this.attempt = attempt;
            return this;
        }

        public Builder agent(String agentId) {
            this.agentId = agentId;
            return this;
        }

        public Builder transition(String from, String to) {
            this.fromState = from;
            this.toState = to;
            return this;
        }

        public Builder actor(String actor) {
            this.actor = actor;
            return this;
        }

        public Builder mode(String executionMode, String model) {
            this.executionMode = executionMode;
            this.model = model;
            return this;
        }

        public Builder refs(List<String> refs) {
            this.refs = refs == null ? List.of() : List.copyOf(refs);
            return this;
        }

        public Builder message(String message) {
            this.message = message;
            return this;
        }

        public Builder details(Map<String, Object> details) {
            this.details = details == null ? Map.of() : details;
            return this;
        }

        public AuditEntry build() {
            return new AuditEntry(runId, taskKey, attempt, agentId, type, fromState, toState, actor, executionMode, model, refs,
                    message, details);
        }
    }
}
