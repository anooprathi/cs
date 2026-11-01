package com.schwab.urlshortener.agentic;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.schwab.urlshortener.agentic.agent.AgentContext;
import com.schwab.urlshortener.agentic.agent.ImpactAnalystAgent;
import com.schwab.urlshortener.agentic.agent.PlannerAgent;
import com.schwab.urlshortener.agentic.agent.PromptSupport;
import com.schwab.urlshortener.agentic.agent.RequirementAnalystAgent;
import com.schwab.urlshortener.agentic.agent.RiskReviewerAgent;
import com.schwab.urlshortener.agentic.agent.SdlcAgent;
import com.schwab.urlshortener.agentic.agent.TestDesignerAgent;
import com.schwab.urlshortener.agentic.model.ExecutionMode;

import java.util.List;

/** Shared, Spring-free building blocks for the agentic tests: the real catalog, real agents, a realistic requirement. */
public final class AgenticFixtures {

    public static final String TITLE = "Max-click limit for short links";

    /** Taken from README §11's backlog ("Maximum-click limit — not implemented"), so it exercises real catalog matches. */
    public static final String REQUIREMENT = """
            Tenants must be able to set an optional maximum click limit when creating a short link.
            Once the limit is reached, the redirect should stop working and return 410 Gone, like an expired link.
            The click count shown in stats must stay accurate under concurrent redirects.
            The redirect path must stay fast.
            Admins should be able to see the limit when listing a tenant's links.
            Bulk editing of existing links is out of scope.
            """;

    public static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule());

    private AgenticFixtures() {
    }

    public static ComponentCatalog catalog() {
        return new ComponentCatalog(MAPPER);
    }

    public static PromptSupport prompts() {
        return new PromptSupport(MAPPER);
    }

    /** Deliberately NOT in execution order, so the orchestrator's own ordering is what's under test. */
    public static List<SdlcAgent<?>> agents() {
        PromptSupport prompts = prompts();
        return List.of(new RiskReviewerAgent(prompts), new TestDesignerAgent(prompts), new PlannerAgent(prompts),
                new ImpactAnalystAgent(prompts), new RequirementAnalystAgent(prompts));
    }

    public static AgentContext context(String title, String requirement) {
        return new AgentContext(title, requirement, ExecutionMode.DETERMINISTIC_DEMO, catalog());
    }

    /** Runs all five deterministic agents in dependency order and returns the filled context. */
    public static AgentContext runDeterministicPipeline(String title, String requirement) {
        AgentContext ctx = context(title, requirement);
        for (SdlcAgent<?> agent : SdlcOrchestrator.order(agents())) {
            ctx.put(agent.stage(), agent.runDeterministic(ctx));
        }
        return ctx;
    }
}
