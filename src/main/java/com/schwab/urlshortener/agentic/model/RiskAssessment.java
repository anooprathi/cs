package com.schwab.urlshortener.agentic.model;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import java.util.List;

/**
 * RISKS stage output. The quality gate blocks approval if any HIGH risk
 * has no mitigation.
 */
public record RiskAssessment(
        @JsonPropertyDescription("Risks, ids R-1, R-2, ...")
        List<Risk> risks
) {

    public enum Severity {
        LOW,
        MEDIUM,
        HIGH
    }

    public record Risk(
            @JsonPropertyDescription("R-n")
            String id,
            String description,
            Severity severity,
            String mitigation,
            @JsonPropertyDescription("Catalog component names the risk concerns")
            List<String> components
    ) {
    }
}
