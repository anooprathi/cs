package com.schwab.urlshortener.workflow.agents;

import com.schwab.urlshortener.agentic.ComponentCatalog;
import com.schwab.urlshortener.agentic.agent.AgentContext;
import com.schwab.urlshortener.agentic.agent.SdlcAgent;
import com.schwab.urlshortener.agentic.model.ImpactAnalysis;
import com.schwab.urlshortener.agentic.model.ImplementationPlan;
import com.schwab.urlshortener.agentic.model.RequirementSpec;
import com.schwab.urlshortener.agentic.model.SdlcStage;
import com.schwab.urlshortener.workflow.adapter.AdapterRouter;
import com.schwab.urlshortener.workflow.adapter.Generation;
import com.schwab.urlshortener.workflow.adapter.GenerationRequest;
import com.schwab.urlshortener.workflow.engine.AgentRequest;
import com.schwab.urlshortener.workflow.engine.ToolPolicy;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Runs one of the agentic layer's reasoning agents (requirements, impact,
 * planner, test designer, risk reviewer) as the generative step of a workflow
 * stage agent, through the {@link AdapterRouter}: the deterministic demo path
 * or a real LLM call, chosen by the run's mode.
 *
 * Context hand-off: upstream agent artifacts come from the request by
 * artifact name, and those reads become lineage. Only the requirement-analysis
 * stage sees the raw requirement text. Every later stage sees the requirement as
 * the normalized FR/NFR statements of the approved requirement-spec, so a
 * requirement edit that doesn't change the spec doesn't change their inputs.
 */
@Component
public class AgentStageRunner {

    private static final Map<SdlcStage, String> ARTIFACT_FOR_STAGE = Map.of(
            SdlcStage.REQUIREMENTS, Artifacts.REQUIREMENT_SPEC,
            SdlcStage.IMPACT, Artifacts.ARCHITECTURE_DESIGN,
            SdlcStage.PLAN, Artifacts.IMPLEMENTATION_PLAN);

    private static final Map<SdlcStage, Class<?>> TYPE_FOR_STAGE = Map.of(
            SdlcStage.REQUIREMENTS, RequirementSpec.class,
            SdlcStage.IMPACT, ImpactAnalysis.class,
            SdlcStage.PLAN, ImplementationPlan.class);

    private final ComponentCatalog catalog;
    private final AdapterRouter router;

    public AgentStageRunner(ComponentCatalog catalog, AdapterRouter router) {
        this.catalog = catalog;
        this.router = router;
    }

    public ComponentCatalog catalog() {
        return catalog;
    }

    public AdapterRouter router() {
        return router;
    }

    public <T> Generation<T> run(SdlcAgent<T> agent, AgentRequest request) {
        request.requireTool(ToolPolicy.Tool.READ_COMPONENT_CATALOG);
        AgentContext ctx = new AgentContext(request.title(), requirementView(agent, request), request.mode(), catalog);
        for (SdlcStage dep : agent.dependsOn()) {
            String name = ARTIFACT_FOR_STAGE.get(dep);
            if (name == null) {
                throw new IllegalStateException("No workflow artifact mapped for agent stage " + dep);
            }
            ctx.put(dep, request.input(name, TYPE_FOR_STAGE.get(dep)));
        }
        String system = agent.systemPrompt() + "\nTask intent: " + request.intent()
                + "\nConstraints:\n- " + String.join("\n- ", request.constraints())
                + "\nYour output will be checked against these acceptance criteria:\n- " + String.join("\n- ", request.acceptanceCriteria());
        return router.generate(new GenerationRequest<>(agent.stage() + " agent", agent.outputType(), system,
                agent.userPrompt(ctx), () -> agent.runDeterministic(ctx)), request);
    }

    /** Raw requirement for the requirements stage; the approved spec's statements for everything after it. */
    private static String requirementView(SdlcAgent<?> agent, AgentRequest request) {
        if (agent.stage() == SdlcStage.REQUIREMENTS) {
            return request.requirement();
        }
        if (!request.hasInput(Artifacts.REQUIREMENT_SPEC)) {
            return request.title();
        }
        RequirementSpec spec = request.input(Artifacts.REQUIREMENT_SPEC, RequirementSpec.class);
        return Stream.concat(Checks.safe(spec.functionalRequirements()).stream(), Checks.safe(spec.nonFunctionalRequirements()).stream())
                .map(RequirementSpec.Statement::text)
                .collect(Collectors.joining("\n"));
    }
}
