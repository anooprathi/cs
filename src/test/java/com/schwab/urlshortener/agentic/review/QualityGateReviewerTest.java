package com.schwab.urlshortener.agentic.review;

import com.schwab.urlshortener.agentic.AgenticFixtures;
import com.schwab.urlshortener.agentic.agent.AgentContext;
import com.schwab.urlshortener.agentic.model.ExecutionMode;
import com.schwab.urlshortener.agentic.model.ImpactAnalysis;
import com.schwab.urlshortener.agentic.model.ImpactAnalysis.ChangeType;
import com.schwab.urlshortener.agentic.model.ImpactAnalysis.ImpactedComponent;
import com.schwab.urlshortener.agentic.model.ImplementationPlan;
import com.schwab.urlshortener.agentic.model.ImplementationPlan.PlanTask;
import com.schwab.urlshortener.agentic.model.QualityGateReport;
import com.schwab.urlshortener.agentic.model.QualityGateReport.Finding;
import com.schwab.urlshortener.agentic.model.QualityGateReport.FindingSeverity;
import com.schwab.urlshortener.agentic.model.RequirementSpec;
import com.schwab.urlshortener.agentic.model.RequirementSpec.Ambiguity;
import com.schwab.urlshortener.agentic.model.RiskAssessment;
import com.schwab.urlshortener.agentic.model.RiskAssessment.Risk;
import com.schwab.urlshortener.agentic.model.SdlcStage;
import com.schwab.urlshortener.agentic.model.TestPlan;
import com.schwab.urlshortener.agentic.model.TestPlan.TestCase;
import com.schwab.urlshortener.agentic.model.TestPlan.TestLevel;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Each gate rule, triggered by a crafted violation of the kind an LLM can
 * plausibly produce. The baseline is a real DETERMINISTIC_DEMO run, which
 * must pass clean. Each test then corrupts exactly one artifact.
 */
class QualityGateReviewerTest {

    private final QualityGateReviewer gate = new QualityGateReviewer();
    private final AgentContext ctx = AgenticFixtures.runDeterministicPipeline(AgenticFixtures.TITLE, AgenticFixtures.REQUIREMENT);

    @Test
    void cleanDeterministicRun_passesWithOnlyProvenanceInfo() {
        QualityGateReport report = gate.review(ctx);

        assertThat(report.passed()).isTrue();
        assertThat(report.blockerCount()).isZero();
        assertThat(report.warningCount()).isZero();
        assertThat(report.findings()).singleElement()
                .satisfies(f -> {
                    assertThat(f.severity()).isEqualTo(FindingSeverity.INFO);
                    assertThat(f.rule()).isEqualTo(QualityGateReviewer.RULE_EXECUTION_MODE);
                    assertThat(f.message()).contains("DETERMINISTIC_DEMO");
                });
        assertThat(report.metrics().criteriaCoveredByTests()).isEqualTo(report.metrics().acceptanceCriteria());
    }

    @Test
    void llmMode_provenanceFindingSaysHumanReviewRequired() {
        AgentContext llm = new AgentContext(ctx.title(), ctx.requirement(), ExecutionMode.LLM, ctx.catalog());
        for (SdlcStage s : List.of(SdlcStage.REQUIREMENTS, SdlcStage.IMPACT, SdlcStage.PLAN, SdlcStage.TESTS, SdlcStage.RISKS)) {
            llm.put(s, artifact(s));
        }

        assertThat(gate.review(llm).findings()).anyMatch(f -> f.message().contains("language model") && f.message().contains("Human review"));
    }

    @Test
    void hallucinatedComponent_isBlocker() {
        ImpactAnalysis impact = ctx.impactAnalysis();
        List<ImpactedComponent> components = new ArrayList<>(impact.impactedComponents());
        components.add(new ImpactedComponent("ClickLimitEnforcerBean", ChangeType.MODIFY, "sounds plausible", List.of("AC-1")));
        ctx.put(SdlcStage.IMPACT, new ImpactAnalysis(components, impact.newComponents(), impact.unaffectedAreas()));

        QualityGateReport report = gate.review(ctx);

        assertThat(report.passed()).isFalse();
        assertThat(blockers(report, QualityGateReviewer.RULE_UNKNOWN_COMPONENT)).singleElement()
                .satisfies(f -> assertThat(f.message()).contains("ClickLimitEnforcerBean"));
        // ...and since no task changes it, also a coverage warning
        assertThat(report.findings()).anyMatch(f -> f.rule().equals(QualityGateReviewer.RULE_COMPONENT_NO_TASK));
    }

    @Test
    void danglingDependency_andCycle_areBlockers() {
        List<PlanTask> tasks = new ArrayList<>(ctx.implementationPlan().tasks());
        tasks.set(0, withDeps(tasks.get(0), List.of("T-2")));          // T-1 -> T-2
        tasks.set(1, withDeps(tasks.get(1), List.of("T-1", "T-99")));  // T-2 -> T-1 (cycle), T-99 (dangling)
        ctx.put(SdlcStage.PLAN, new ImplementationPlan(tasks));

        QualityGateReport report = gate.review(ctx);

        assertThat(report.passed()).isFalse();
        assertThat(blockers(report, QualityGateReviewer.RULE_DANGLING_DEPENDENCY)).singleElement()
                .satisfies(f -> assertThat(f.message()).contains("T-99"));
        assertThat(blockers(report, QualityGateReviewer.RULE_CYCLE)).singleElement()
                .satisfies(f -> assertThat(f.message()).contains("T-1 -> T-2 -> T-1"));
    }

    @Test
    void findCycle_acyclicGraph_isEmpty_andSelfLoopIsACycle() {
        assertThat(QualityGateReviewer.findCycle(ctx.implementationPlan().tasks())).isEmpty();
        PlanTask self = new PlanTask("T-1", "t", "d", List.of("T-1"), List.of(), List.of());
        assertThat(QualityGateReviewer.findCycle(List.of(self))).contains(List.of("T-1", "T-1"));
    }

    @Test
    void criterionWithNoTaskOrTest_isBlockerForEach() {
        RequirementSpec spec = ctx.requirementSpec();
        List<RequirementSpec.AcceptanceCriterion> acs = new ArrayList<>(spec.acceptanceCriteria());
        acs.add(new RequirementSpec.AcceptanceCriterion("AC-99", "g", "w", "t", "FR-1"));
        ctx.put(SdlcStage.REQUIREMENTS, new RequirementSpec(spec.title(), spec.summary(), spec.functionalRequirements(),
                spec.nonFunctionalRequirements(), acs, spec.ambiguities(), spec.outOfScope()));

        QualityGateReport report = gate.review(ctx);

        assertThat(blockers(report, QualityGateReviewer.RULE_CRITERION_NO_TASK)).extracting(Finding::message).containsExactly(
                "AC-99 is not covered by any implementation task.");
        assertThat(blockers(report, QualityGateReviewer.RULE_CRITERION_NO_TEST)).extracting(Finding::message).containsExactly(
                "AC-99 is not verified by any test case.");
        assertThat(report.metrics().acceptanceCriteria()).isEqualTo(acs.size());
        assertThat(report.metrics().criteriaCoveredByTests()).isEqualTo(acs.size() - 1);
    }

    @Test
    void referenceToNonexistentCriterion_isBlocker() {
        List<TestCase> cases = new ArrayList<>(ctx.testPlan().testCases());
        cases.add(new TestCase("TC-99", "t", TestLevel.UNIT, "X", "s", List.of("AC-42")));
        ctx.put(SdlcStage.TESTS, new TestPlan(cases));

        assertThat(blockers(gate.review(ctx), QualityGateReviewer.RULE_UNKNOWN_CRITERION)).singleElement()
                .satisfies(f -> assertThat(f.message()).contains("TC-99").contains("AC-42"));
    }

    @Test
    void duplicateAndMissingIds_areBlockers() {
        List<TestCase> cases = new ArrayList<>(ctx.testPlan().testCases());
        cases.add(cases.get(0));
        cases.add(new TestCase(null, "t", TestLevel.UNIT, "X", "s", List.of()));
        ctx.put(SdlcStage.TESTS, new TestPlan(cases));

        List<Finding> dupes = blockers(gate.review(ctx), QualityGateReviewer.RULE_DUPLICATE_ID);
        assertThat(dupes).extracting(Finding::message)
                .containsExactlyInAnyOrder("A test case has no id.", "Duplicate test case id 'TC-1' (2 occurrences).");
    }

    @Test
    void highRiskWithoutMitigation_isBlocker_lowRiskWithoutMitigationIsNot() {
        List<Risk> risks = new ArrayList<>(ctx.riskAssessment().risks());
        risks.add(new Risk("R-90", "high and unhandled", RiskAssessment.Severity.HIGH, " ", List.of()));
        risks.add(new Risk("R-91", "low and unhandled", RiskAssessment.Severity.LOW, null, List.of()));
        ctx.put(SdlcStage.RISKS, new RiskAssessment(risks));

        assertThat(blockers(gate.review(ctx), QualityGateReviewer.RULE_HIGH_RISK_UNMITIGATED)).extracting(Finding::message)
                .containsExactly("HIGH risk R-90 has no mitigation.");
    }

    @Test
    void taskOnUnknownComponent_andAmbiguityWithoutAssumption_areWarningsOnly() {
        List<PlanTask> tasks = new ArrayList<>(ctx.implementationPlan().tasks());
        PlanTask t = tasks.get(0);
        tasks.set(0, new PlanTask(t.id(), t.title(), t.description(), t.dependsOn(), List.of("UrlMapping", "MysteryHelper"),
                t.acceptanceCriteria()));
        ctx.put(SdlcStage.PLAN, new ImplementationPlan(tasks));
        RequirementSpec spec = ctx.requirementSpec();
        ctx.put(SdlcStage.REQUIREMENTS, new RequirementSpec(spec.title(), spec.summary(), spec.functionalRequirements(),
                spec.nonFunctionalRequirements(), spec.acceptanceCriteria(),
                List.of(new Ambiguity("AMB-1", "vague", "what?", "")), spec.outOfScope()));

        QualityGateReport report = gate.review(ctx);

        assertThat(report.passed()).isTrue();
        assertThat(report.warningCount()).isEqualTo(2);
        assertThat(report.findings()).extracting(Finding::rule)
                .contains(QualityGateReviewer.RULE_TASK_UNKNOWN_COMPONENT, QualityGateReviewer.RULE_AMBIGUITY_NO_ASSUMPTION);
    }

    @Test
    void emptyOrNullLists_failCleanlyInsteadOfThrowing() {
        ctx.put(SdlcStage.REQUIREMENTS, new RequirementSpec("t", "s", null, null, null, null, null));
        ctx.put(SdlcStage.IMPACT, new ImpactAnalysis(null, null, null));
        ctx.put(SdlcStage.PLAN, new ImplementationPlan(null));
        ctx.put(SdlcStage.TESTS, new TestPlan(null));
        ctx.put(SdlcStage.RISKS, new RiskAssessment(null));

        QualityGateReport report = gate.review(ctx);

        assertThat(report.passed()).isFalse();
        assertThat(blockers(report, QualityGateReviewer.RULE_NO_CRITERIA)).hasSize(1);
    }

    @Test
    void compareIds_ordersNumericSuffixesNumerically() {
        assertThat(QualityGateReviewer.compareIds("AC-2", "AC-10")).isNegative();
        assertThat(QualityGateReviewer.compareIds("AC-10", "AC-2")).isPositive();
        assertThat(QualityGateReviewer.compareIds("AC-x", "AC-y")).isNegative();
        assertThat(QualityGateReviewer.compareIds("AC-1", "T-1")).isNegative();
    }

    private Object artifact(SdlcStage stage) {
        return switch (stage) {
            case REQUIREMENTS -> ctx.requirementSpec();
            case IMPACT -> ctx.impactAnalysis();
            case PLAN -> ctx.implementationPlan();
            case TESTS -> ctx.testPlan();
            case RISKS -> ctx.riskAssessment();
            case REVIEW -> throw new IllegalArgumentException();
        };
    }

    private static PlanTask withDeps(PlanTask t, List<String> deps) {
        return new PlanTask(t.id(), t.title(), t.description(), deps, t.components(), t.acceptanceCriteria());
    }

    private static List<Finding> blockers(QualityGateReport report, String rule) {
        return report.findings().stream()
                .filter(f -> f.severity() == FindingSeverity.BLOCKER && f.rule().equals(rule))
                .toList();
    }
}
