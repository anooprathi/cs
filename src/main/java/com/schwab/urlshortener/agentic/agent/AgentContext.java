package com.schwab.urlshortener.agentic.agent;

import com.schwab.urlshortener.agentic.ComponentCatalog;
import com.schwab.urlshortener.agentic.model.ExecutionMode;
import com.schwab.urlshortener.agentic.model.ImpactAnalysis;
import com.schwab.urlshortener.agentic.model.ImplementationPlan;
import com.schwab.urlshortener.agentic.model.RequirementSpec;
import com.schwab.urlshortener.agentic.model.RiskAssessment;
import com.schwab.urlshortener.agentic.model.SdlcStage;
import com.schwab.urlshortener.agentic.model.TestPlan;

import java.util.EnumMap;
import java.util.Map;

/**
 * Everything one run's agents can see: the raw requirement, the codebase
 * catalog, and the artifacts produced by stages that already ran. Mutable
 * only through {@link #put}, which the orchestrator calls after a stage
 * succeeds — an agent can read upstream artifacts but never another
 * stage's slot.
 *
 * The typed getters throw if an upstream artifact is missing rather than
 * returning null, so an agent wired to run before its dependency fails
 * loudly instead of producing an artifact built on nothing.
 */
public class AgentContext {

    private final String title;
    private final String requirement;
    private final ExecutionMode mode;
    private final ComponentCatalog catalog;
    private final Map<SdlcStage, Object> artifacts = new EnumMap<>(SdlcStage.class);

    public AgentContext(String title, String requirement, ExecutionMode mode, ComponentCatalog catalog) {
        this.title = title;
        this.requirement = requirement;
        this.mode = mode;
        this.catalog = catalog;
    }

    public String title() {
        return title;
    }

    public String requirement() {
        return requirement;
    }

    public ExecutionMode mode() {
        return mode;
    }

    public ComponentCatalog catalog() {
        return catalog;
    }

    public void put(SdlcStage stage, Object artifact) {
        artifacts.put(stage, artifact);
    }

    public boolean has(SdlcStage stage) {
        return artifacts.containsKey(stage);
    }

    public RequirementSpec requirementSpec() {
        return get(SdlcStage.REQUIREMENTS, RequirementSpec.class);
    }

    public ImpactAnalysis impactAnalysis() {
        return get(SdlcStage.IMPACT, ImpactAnalysis.class);
    }

    public ImplementationPlan implementationPlan() {
        return get(SdlcStage.PLAN, ImplementationPlan.class);
    }

    public TestPlan testPlan() {
        return get(SdlcStage.TESTS, TestPlan.class);
    }

    public RiskAssessment riskAssessment() {
        return get(SdlcStage.RISKS, RiskAssessment.class);
    }

    private <T> T get(SdlcStage stage, Class<T> type) {
        Object artifact = artifacts.get(stage);
        if (artifact == null) {
            throw new IllegalStateException(stage + " artifact requested before that stage produced one");
        }
        return type.cast(artifact);
    }
}
