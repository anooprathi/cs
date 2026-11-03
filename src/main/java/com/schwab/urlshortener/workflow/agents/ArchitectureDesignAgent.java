package com.schwab.urlshortener.workflow.agents;

import com.schwab.urlshortener.agentic.agent.ImpactAnalystAgent;
import com.schwab.urlshortener.agentic.model.ImpactAnalysis;
import com.schwab.urlshortener.agentic.model.RequirementSpec;
import com.schwab.urlshortener.workflow.adapter.Generation;
import com.schwab.urlshortener.workflow.engine.AgentRequest;
import com.schwab.urlshortener.workflow.engine.AgentResult;
import com.schwab.urlshortener.workflow.engine.GateCriterion;
import com.schwab.urlshortener.workflow.engine.GateCriterion.Result;
import com.schwab.urlshortener.workflow.engine.GateInput;
import com.schwab.urlshortener.workflow.engine.StageAgent;
import com.schwab.urlshortener.workflow.engine.ToolPolicy;
import com.schwab.urlshortener.workflow.graph.WorkflowStage;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * ARCHITECTURE_DESIGN stage agent: requirement-spec → architecture-design, the
 * analysis of which real components of this codebase change and why (plus
 * proposed new ones), grounded in the component catalog. A component name not
 * in the catalog fails the exit gate. That's the guard against a model
 * inventing classes.
 */
@Component
public class ArchitectureDesignAgent implements StageAgent {

    private final ImpactAnalystAgent agent;
    private final AgentStageRunner runner;

    public ArchitectureDesignAgent(ImpactAnalystAgent agent, AgentStageRunner runner) {
        this.agent = agent;
        this.runner = runner;
    }

    @Override
    public String key() {
        return "architecture-design";
    }

    @Override
    public String version() {
        return "3";
    }

    @Override
    public WorkflowStage stage() {
        return WorkflowStage.ARCHITECTURE_DESIGN;
    }

    @Override
    public String description() {
        return "Maps the requirement onto real components of this codebase (component catalog): what changes, why, and what is new.";
    }

    @Override
    public String intent() {
        return "Identify the existing modules, APIs, persistence, and tests this requirement affects, and anything new it needs.";
    }

    @Override
    public List<String> constraints() {
        return List.of("Name only components that exist in the component catalog; put anything new under newComponents.",
                "Relate each change to the acceptance-criterion ids it serves.");
    }

    @Override
    public List<String> consumes() {
        return List.of(Artifacts.REQUIREMENT_SPEC);
    }

    @Override
    public List<String> produces() {
        return List.of(Artifacts.ARCHITECTURE_DESIGN);
    }

    @Override
    public boolean supportsDeterministicFallback() {
        return true;
    }

    @Override
    public ToolPolicy toolPolicy() {
        return ToolPolicy.of(ToolPolicy.Tool.READ_ARTIFACTS, ToolPolicy.Tool.READ_COMPONENT_CATALOG, ToolPolicy.Tool.LLM_GENERATE);
    }

    @Override
    public List<GateCriterion> entryCriteria() {
        return List.of(new GateCriterion("DES-ENTRY-1", "Upstream requirement-spec has at least one acceptance criterion",
                in -> {
                    int n = Checks.safe(in.input(Artifacts.REQUIREMENT_SPEC, RequirementSpec.class).acceptanceCriteria()).size();
                    return Result.of(n > 0, n + " criteria", "requirement-spec has no acceptance criteria");
                }));
    }

    @Override
    public List<GateCriterion> exitCriteria() {
        return List.of(
                new GateCriterion("DES-EXIT-1", "Every impacted component exists in the codebase's component catalog",
                        in -> {
                            List<String> unknown = Checks.safe(design(in).impactedComponents()).stream()
                                    .map(ImpactAnalysis.ImpactedComponent::component)
                                    .filter(c -> !runner.catalog().contains(c)).toList();
                            return Result.of(unknown.isEmpty(), "all grounded", "unknown components: " + unknown);
                        }),
                new GateCriterion("DES-EXIT-2", "The design names at least one impacted or proposed component",
                        in -> {
                            ImpactAnalysis d = design(in);
                            int n = Checks.safe(d.impactedComponents()).size() + Checks.safe(d.newComponents()).size();
                            return Result.of(n > 0, n + " components", "design names no components");
                        }),
                new GateCriterion("DES-EXIT-3", "Related criteria reference existing acceptance criterion ids",
                        in -> {
                            Set<String> known = Checks.criterionIds(in.input(Artifacts.REQUIREMENT_SPEC, RequirementSpec.class));
                            List<String> unknown = Checks.safe(design(in).impactedComponents()).stream()
                                    .flatMap(c -> Checks.safe(c.relatedCriteria()).stream())
                                    .filter(id -> !known.contains(id)).distinct().toList();
                            return Result.of(unknown.isEmpty(), "all resolve", "unknown criteria: " + unknown);
                        }),
                new GateCriterion("DES-EXIT-4", "At least one data flow is described, and every step is a catalog or proposed component",
                        in -> {
                            ImpactAnalysis d = design(in);
                            List<ImpactAnalysis.DataFlow> flows = Checks.safe(d.dataFlows());
                            Set<String> proposed = new java.util.HashSet<>();
                            Checks.safe(d.newComponents()).forEach(p -> proposed.add(p.name()));
                            List<String> unknown = flows.stream().flatMap(f -> Checks.safe(f.steps()).stream())
                                    .filter(s -> !runner.catalog().contains(s) && !proposed.contains(s)).distinct().toList();
                            return Result.of(!flows.isEmpty() && unknown.isEmpty(), flows.size() + " data flow(s), all grounded",
                                    flows.isEmpty() ? "no data flow described" : "unknown components in data flows: " + unknown);
                        }));
    }

    private static ImpactAnalysis design(GateInput in) {
        return in.output(Artifacts.ARCHITECTURE_DESIGN, ImpactAnalysis.class);
    }

    @Override
    public AgentResult execute(AgentRequest request) {
        Generation<ImpactAnalysis> g = runner.run(agent, request);
        ImpactAnalysis design = g.output();
        List<String> impacted = Checks.safe(design.impactedComponents()).stream()
                .map(c -> c.component() + " (" + c.changeType() + ")").toList();
        long grounded = Checks.safe(design.impactedComponents()).stream().filter(c -> runner.catalog().contains(c.component())).count();
        return new AgentResult(AgentResult.Status.COMPLETED,
                "Design touches " + impacted.size() + " existing components and proposes "
                        + Checks.safe(design.newComponents()).size() + " new ones.",
                List.of(new AgentResult.Produced(Artifacts.ARCHITECTURE_DESIGN, design, g.origin(), g.model())),
                List.of(Checks.evidence("impacted components grounded in catalog", grounded == impacted.size(),
                        grounded + "/" + impacted.size() + " found in catalog")),
                List.of(), Checks.safe(design.unaffectedAreas()).isEmpty() ? List.of()
                        : List.of("Areas assumed unaffected: " + Checks.safe(design.unaffectedAreas()).size()),
                List.of(Checks.decision("Impacted: " + Checks.listOrNone(impacted) + ".",
                                "Matched against the curated component catalog of this repository."),
                        Checks.decision("Produced by " + Checks.producedBy(g) + ".", "Execution mode of the run.")),
                null, null, g.usage());
    }
}
