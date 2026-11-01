package com.schwab.urlshortener.agentic.agent;

import com.schwab.urlshortener.agentic.ComponentCatalog;
import com.schwab.urlshortener.agentic.ComponentCatalog.CatalogComponent;
import com.schwab.urlshortener.agentic.ComponentCatalog.Layer;
import com.schwab.urlshortener.agentic.model.ImpactAnalysis;
import com.schwab.urlshortener.agentic.model.ImpactAnalysis.ImpactedComponent;
import com.schwab.urlshortener.agentic.model.RequirementSpec;
import com.schwab.urlshortener.agentic.model.RequirementSpec.AcceptanceCriterion;
import com.schwab.urlshortener.agentic.model.RequirementSpec.Ambiguity;
import com.schwab.urlshortener.agentic.model.SdlcStage;
import com.schwab.urlshortener.agentic.model.TestPlan;
import com.schwab.urlshortener.agentic.model.TestPlan.TestCase;
import com.schwab.urlshortener.agentic.model.TestPlan.TestLevel;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * TESTS stage: test cases traced to acceptance criteria.
 *
 * Deterministic rules: one test case per acceptance criterion. Its target
 * class is an existing test listed in the catalog for the outermost-layer
 * impacted component related to that criterion (so the plan points at
 * real test files in this repo); its level is INTEGRATION when an API- or
 * SECURITY-layer component is involved, otherwise UNIT. Each ambiguity
 * also gets a test pinning down the assumption taken — if the assumption
 * later turns out wrong, there's one obvious test to change. A final
 * regression case covers the existing suite.
 */
@Component
public class TestDesignerAgent implements SdlcAgent<TestPlan> {

    /**
     * Which related component's test file a case targets: the outermost layer wins, so an
     * INTEGRATION-level case lands in an integration/web test rather than an entity mapper test.
     */
    private static final List<Layer> TARGET_PRIORITY = List.of(
            Layer.API, Layer.SECURITY, Layer.SERVICE, Layer.CROSS_CUTTING, Layer.DATA, Layer.CONFIG);

    static final String FALLBACK_TEST_CLASS = "src/test/java/com/schwab/urlshortener/integration/UrlShortenerIntegrationTest.java";

    private final PromptSupport prompts;

    public TestDesignerAgent(PromptSupport prompts) {
        this.prompts = prompts;
    }

    @Override
    public SdlcStage stage() {
        return SdlcStage.TESTS;
    }

    @Override
    public List<SdlcStage> dependsOn() {
        return List.of(SdlcStage.REQUIREMENTS, SdlcStage.IMPACT, SdlcStage.PLAN);
    }

    @Override
    public Class<TestPlan> outputType() {
        return TestPlan.class;
    }

    @Override
    public TestPlan runDeterministic(AgentContext context) {
        RequirementSpec spec = context.requirementSpec();
        ImpactAnalysis impact = context.impactAnalysis();
        ComponentCatalog catalog = context.catalog();

        boolean touchesWebOrSecurity = impact.impactedComponents().stream()
                .map(ic -> catalog.find(ic.component()))
                .flatMap(Optional::stream)
                .anyMatch(c -> c.layer() == Layer.API || c.layer() == Layer.SECURITY);
        TestLevel defaultLevel = touchesWebOrSecurity ? TestLevel.INTEGRATION : TestLevel.UNIT;

        List<TestCase> cases = new ArrayList<>();
        for (AcceptanceCriterion ac : spec.acceptanceCriteria()) {
            List<CatalogComponent> related = impact.impactedComponents().stream()
                    .filter(ic -> ic.relatedCriteria().contains(ac.id()))
                    .map(ImpactedComponent::component)
                    .map(catalog::find)
                    .flatMap(Optional::stream)
                    .toList();
            String target = related.stream()
                    .sorted(Comparator.comparingInt(c -> TARGET_PRIORITY.indexOf(c.layer())))
                    .flatMap(c -> c.tests().stream())
                    .findFirst()
                    .orElse(FALLBACK_TEST_CLASS);
            TestLevel level = related.isEmpty()
                    ? defaultLevel
                    : related.stream().anyMatch(c -> c.layer() == Layer.API || c.layer() == Layer.SECURITY)
                    ? TestLevel.INTEGRATION : TestLevel.UNIT;
            cases.add(new TestCase("TC-" + (cases.size() + 1), "Verify " + ac.id() + " (" + ac.sourceRequirementId() + ")",
                    level, target, "Given " + ac.given() + ", when " + ac.when() + ", then " + ac.then() + ".",
                    List.of(ac.id())));
        }
        for (Ambiguity amb : spec.ambiguities()) {
            cases.add(new TestCase("TC-" + (cases.size() + 1), "Pin assumption " + amb.id(), defaultLevel, FALLBACK_TEST_CLASS,
                    "Asserts the behavior assumed for " + amb.id() + ": " + amb.assumption()
                            + " Update this test if the requirement owner answers differently.",
                    List.of()));
        }
        cases.add(new TestCase("TC-" + (cases.size() + 1), "Existing suite stays green", TestLevel.INTEGRATION, "mvn clean verify",
                "All pre-existing tests pass unchanged and line coverage stays at or above the 80% JaCoCo gate.",
                List.of()));
        return new TestPlan(cases);
    }

    @Override
    public String systemPrompt() {
        return prompts.system("""
                Role: test designer.
                Design test cases (TC-n) so that every acceptance criterion is verified by at least one case. \
                For each, pick a level (UNIT, WEB_SLICE, INTEGRATION, MANUAL), a target JUnit test class — prefer \
                an existing test file of the impacted component from the catalog — a given/when/then scenario, \
                and the AC-n ids it verifies. Include negative-path cases (invalid input, unauthorized, \
                cross-tenant access returning 404) where the requirement touches the API.
                """);
    }

    @Override
    public String userPrompt(AgentContext context) {
        return prompts.requirementBlock(context)
                + prompts.catalogBlockWithTests(context.catalog())
                + prompts.artifactBlock("Requirement spec", context.requirementSpec())
                + prompts.artifactBlock("Impact analysis", context.impactAnalysis())
                + prompts.artifactBlock("Implementation plan", context.implementationPlan());
    }
}
