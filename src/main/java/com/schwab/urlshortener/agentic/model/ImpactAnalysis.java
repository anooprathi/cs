package com.schwab.urlshortener.agentic.model;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import java.util.List;

/**
 * IMPACT stage output: which existing components of THIS codebase a change
 * touches, and why. Component names must come from the component catalog
 * (agentic/component-catalog.json) — the quality gate rejects any name it
 * can't find there, which is the main guard against a model inventing a
 * plausible-sounding class that doesn't exist.
 */
public record ImpactAnalysis(
        @JsonPropertyDescription("Existing components affected; 'component' must be a name from the provided catalog")
        List<ImpactedComponent> impactedComponents,
        @JsonPropertyDescription("Components that do not exist yet and would need to be created")
        List<ProposedComponent> newComponents,
        @JsonPropertyDescription("Notable areas deliberately left unchanged, with a short reason")
        List<String> unaffectedAreas
) {

    public enum ChangeType {
        MODIFY,
        EXTEND,
        CONFIGURE,
        TEST_ONLY
    }

    public record ImpactedComponent(
            @JsonPropertyDescription("Exact component name from the catalog")
            String component,
            ChangeType changeType,
            String reason,
            @JsonPropertyDescription("AC-n ids this component's change is needed for")
            List<String> relatedCriteria
    ) {
    }

    public record ProposedComponent(
            String name,
            String packageName,
            String responsibility
    ) {
    }
}
