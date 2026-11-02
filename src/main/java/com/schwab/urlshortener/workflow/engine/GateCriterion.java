package com.schwab.urlshortener.workflow.engine;

import java.util.function.Function;

/**
 * One explicit, named acceptance criterion of an entry or exit gate, e.g.
 * {@code REQ-EXIT-2 "Every FR/NFR is verified by at least one acceptance criterion"}.
 * The id and description are what the API, the history, and lineage show. The
 * check is deterministic code, never a model call.
 */
public record GateCriterion(String id, String description, Function<GateInput, Result> check) {

    public record Result(boolean passed, String detail) {
        public static Result pass(String detail) {
            return new Result(true, detail);
        }

        public static Result fail(String detail) {
            return new Result(false, detail);
        }

        public static Result of(boolean passed, String passDetail, String failDetail) {
            return passed ? pass(passDetail) : fail(failDetail);
        }
    }
}
