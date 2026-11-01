package com.schwab.urlshortener.agentic.model;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import java.util.List;

/**
 * REQUIREMENTS stage output: the free-text requirement normalized into
 * numbered statements, testable acceptance criteria, and explicitly
 * surfaced ambiguities (each with the assumption the rest of the pipeline
 * proceeds on) — the same "normalize, don't silently guess" discipline
 * ENGINEERING_SUMMARY.md §3 applied by hand to "add analytics".
 *
 * The @JsonPropertyDescription annotations are not decoration: in LLM mode
 * this record IS the structured-output JSON schema sent to the model, and
 * the descriptions are how the model learns each field's contract.
 */
public record RequirementSpec(
        @JsonPropertyDescription("Short name of the requirement")
        String title,
        @JsonPropertyDescription("One or two sentence restatement of what is being asked for")
        String summary,
        @JsonPropertyDescription("Functional requirements, ids FR-1, FR-2, ...")
        List<Statement> functionalRequirements,
        @JsonPropertyDescription("Non-functional requirements (performance, security, reliability, ...), ids NFR-1, NFR-2, ...")
        List<Statement> nonFunctionalRequirements,
        @JsonPropertyDescription("Testable acceptance criteria, ids AC-1, AC-2, ...; every FR and NFR needs at least one")
        List<AcceptanceCriterion> acceptanceCriteria,
        @JsonPropertyDescription("Vague or missing information, ids AMB-1, AMB-2, ..., each with the assumption taken")
        List<Ambiguity> ambiguities,
        @JsonPropertyDescription("Things explicitly not being delivered")
        List<String> outOfScope
) {

    public record Statement(
            @JsonPropertyDescription("FR-n or NFR-n")
            String id,
            String text
    ) {
    }

    public record AcceptanceCriterion(
            @JsonPropertyDescription("AC-n")
            String id,
            String given,
            String when,
            String then,
            @JsonPropertyDescription("The FR-n or NFR-n this criterion verifies")
            String sourceRequirementId
    ) {
    }

    public record Ambiguity(
            @JsonPropertyDescription("AMB-n")
            String id,
            @JsonPropertyDescription("The vague phrase or the gap, quoted from or about the requirement")
            String statement,
            @JsonPropertyDescription("The question a requirement owner must answer")
            String question,
            @JsonPropertyDescription("The assumption the plan proceeds on until the question is answered")
            String assumption
    ) {
    }
}
