package com.schwab.urlshortener.workflow.engine;

import java.util.ArrayList;
import java.util.List;

/** Evaluated gate: every criterion with its outcome. The gate passes only if every criterion passes. */
public record GateResult(Kind kind, boolean passed, List<Outcome> criteria) {

    public enum Kind {
        ENTRY,
        EXIT
    }

    public record Outcome(String id, String description, boolean passed, String detail) {
    }

    /**
     * Evaluates every criterion, even after a failure, so the record shows the full
     * picture. A criterion that throws counts as failed, with the exception type as
     * its detail. A gate can never pass because a check crashed.
     */
    public static GateResult evaluate(Kind kind, List<GateCriterion> criteria, GateInput input) {
        List<Outcome> outcomes = new ArrayList<>();
        for (GateCriterion c : criteria) {
            GateCriterion.Result r;
            try {
                r = c.check().apply(input);
            } catch (RuntimeException e) {
                r = GateCriterion.Result.fail("Criterion could not be evaluated: " + e.getClass().getSimpleName()
                        + (e.getMessage() == null ? "" : ": " + e.getMessage()));
            }
            outcomes.add(new Outcome(c.id(), c.description(), r.passed(), r.detail()));
        }
        return new GateResult(kind, outcomes.stream().allMatch(Outcome::passed), List.copyOf(outcomes));
    }

    public List<String> failedIds() {
        return criteria.stream().filter(o -> !o.passed()).map(Outcome::id).toList();
    }
}
