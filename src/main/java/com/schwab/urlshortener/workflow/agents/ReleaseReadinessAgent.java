package com.schwab.urlshortener.workflow.agents;

import com.schwab.urlshortener.agentic.model.ImplementationPlan;
import com.schwab.urlshortener.agentic.model.RequirementSpec;
import com.schwab.urlshortener.agentic.model.RiskAssessment;
import com.schwab.urlshortener.workflow.artifact.DocumentationBundle;
import com.schwab.urlshortener.workflow.artifact.PolicyReport;
import com.schwab.urlshortener.workflow.artifact.ReadinessReport;
import com.schwab.urlshortener.workflow.artifact.TestReport;
import com.schwab.urlshortener.workflow.engine.AgentRequest;
import com.schwab.urlshortener.workflow.engine.AgentResult;
import com.schwab.urlshortener.workflow.engine.ArtifactOrigin;
import com.schwab.urlshortener.workflow.engine.ArtifactRef;
import com.schwab.urlshortener.workflow.engine.GateCriterion;
import com.schwab.urlshortener.workflow.engine.GateCriterion.Result;
import com.schwab.urlshortener.workflow.engine.Hashes;
import com.schwab.urlshortener.workflow.engine.StageAgent;
import com.schwab.urlshortener.workflow.engine.ToolPolicy;
import com.schwab.urlshortener.workflow.graph.WorkflowStage;
import com.schwab.urlshortener.workflow.workspace.ChangePatch;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * RELEASE_READINESS stage agent: a deterministic checklist over every upstream
 * report and the patch. In the standard graph this task is approval-gated: once
 * the exit gate passes it waits in WAITING_APPROVAL. The approval is recorded
 * against the exact readiness-report version, and through
 * {@link ReadinessReport#approvalSubject()} against the exact change-patch version.
 *
 * The entry gate re-checks the facts this stage depends on (tests passed, policy
 * clean, patch applied) rather than trusting that upstream exit gates ran.
 */
@Component
public class ReleaseReadinessAgent implements StageAgent {

    @Override
    public String key() {
        return "release-readiness";
    }

    @Override
    public String version() {
        return "2";
    }

    @Override
    public WorkflowStage stage() {
        return WorkflowStage.RELEASE_READINESS;
    }

    @Override
    public String description() {
        return "Computes a release-readiness checklist from test, policy, risk, documentation, and patch results for human approval.";
    }

    @Override
    public String intent() {
        return "Decide whether the change is ready for a human to approve promotion, with every check stated explicitly.";
    }

    @Override
    public List<String> constraints() {
        return List.of("Blocking checks cannot be waived by the agent.", "Never promote, merge, or deploy: only report readiness.");
    }

    @Override
    public List<String> consumes() {
        return List.of(Artifacts.REQUIREMENT_SPEC, Artifacts.IMPLEMENTATION_PLAN, Artifacts.CHANGE_PATCH, Artifacts.TEST_REPORT,
                Artifacts.POLICY_REPORT, Artifacts.RISK_ASSESSMENT, Artifacts.DOCUMENTATION);
    }

    @Override
    public List<String> produces() {
        return List.of(Artifacts.READINESS_REPORT);
    }

    @Override
    public ToolPolicy toolPolicy() {
        return ToolPolicy.of(ToolPolicy.Tool.READ_ARTIFACTS);
    }

    @Override
    public List<GateCriterion> entryCriteria() {
        return List.of(
                new GateCriterion("REL-ENTRY-1", "The test report shows every executed test case passed",
                        in -> {
                            TestReport r = in.input(Artifacts.TEST_REPORT, TestReport.class);
                            return Result.of(r.allExecutedPassed(), r.passed() + "/" + r.executed() + " passed",
                                    r.failed() + " failed of " + r.executed() + " executed");
                        }),
                new GateCriterion("REL-ENTRY-2", "The policy report has no BLOCKER violations",
                        in -> {
                            PolicyReport p = in.input(Artifacts.POLICY_REPORT, PolicyReport.class);
                            return Result.of(p.blockers() == 0, "no blockers", p.blockers() + " blocker(s)");
                        }));
    }

    @Override
    public List<GateCriterion> exitCriteria() {
        return List.of(new GateCriterion("REL-EXIT-1", "Every blocking readiness check passed",
                in -> {
                    ReadinessReport r = in.output(Artifacts.READINESS_REPORT, ReadinessReport.class);
                    List<String> failed = r.checks().stream().filter(c -> c.blocking() && !c.passed()).map(ReadinessReport.Check::id).toList();
                    return Result.of(failed.isEmpty(), "ready", "failed checks " + failed);
                }));
    }

    @Override
    public AgentResult execute(AgentRequest request) {
        RequirementSpec spec = request.input(Artifacts.REQUIREMENT_SPEC, RequirementSpec.class);
        ImplementationPlan plan = request.input(Artifacts.IMPLEMENTATION_PLAN, ImplementationPlan.class);
        ChangePatch patch = request.input(Artifacts.CHANGE_PATCH, ChangePatch.class);
        TestReport tests = request.input(Artifacts.TEST_REPORT, TestReport.class);
        PolicyReport policy = request.input(Artifacts.POLICY_REPORT, PolicyReport.class);
        RiskAssessment risks = request.input(Artifacts.RISK_ASSESSMENT, RiskAssessment.class);
        DocumentationBundle docs = request.input(Artifacts.DOCUMENTATION, DocumentationBundle.class);

        Set<String> traced = new HashSet<>();
        docs.traceability().forEach(r -> traced.add(r.criterionId()));
        List<String> untraced = Checks.criterionIds(spec).stream().filter(id -> !traced.contains(id)).toList();
        long unmitigatedHigh = Checks.safe(risks.risks()).stream()
                .filter(r -> r.severity() == RiskAssessment.Severity.HIGH && (r.mitigation() == null || r.mitigation().isBlank())).count();

        List<ReadinessReport.Check> checks = new ArrayList<>();
        checks.add(new ReadinessReport.Check("RR-1", "All executed test cases passed", true, tests.allExecutedPassed(),
                tests.passed() + "/" + tests.executed() + " passed via '" + tests.runner() + "'"));
        checks.add(new ReadinessReport.Check("RR-2", "No BLOCKER policy violations", true, policy.blockers() == 0,
                policy.blockers() + " blocker(s), " + policy.warnings() + " warning(s)"));
        checks.add(new ReadinessReport.Check("RR-3", "Every acceptance criterion is documented and traced", true, untraced.isEmpty(),
                untraced.isEmpty() ? "all traced" : "untraced " + untraced));
        checks.add(new ReadinessReport.Check("RR-4", "No HIGH risk lacks a mitigation", true, unmitigatedHigh == 0,
                unmitigatedHigh + " unmitigated"));
        checks.add(new ReadinessReport.Check("RR-5", "The patch passed change control and every validation command", true,
                patch.policyViolations().isEmpty() && patch.validationPassed(),
                patch.validation().size() + " validation command(s); " + patch.policyViolations().size() + " violation(s)"));
        checks.add(new ReadinessReport.Check("RR-6", "Product test suite actually executed", false, tests.productTestsExecuted(),
                tests.productTestsExecuted() ? "executed by '" + tests.runner() + "'"
                        : "not executed: '" + tests.runner() + "' verifies the plan only. Run the real suite before releasing."));
        checks.add(new ReadinessReport.Check("RR-7", "No open assumptions", false, Checks.safe(spec.ambiguities()).isEmpty(),
                Checks.safe(spec.ambiguities()).size() + " assumption(s) were approved, not confirmed by the requirement owner"));
        checks.add(new ReadinessReport.Check("RR-8", "No policy warnings", false, policy.warnings() == 0, policy.warnings() + " warning(s)"));
        checks.add(new ReadinessReport.Check("RR-9", "The patch is product code (not a demo change record)", false,
                patch.notice() == null || !patch.notice().startsWith("DETERMINISTIC_DEMO"),
                patch.notice() == null ? "-" : patch.notice()));

        List<String> subject = new ArrayList<>();
        for (String name : List.of(Artifacts.CHANGE_PATCH, Artifacts.TEST_REPORT, Artifacts.POLICY_REPORT)) {
            ArtifactRef ref = request.inputRefs().get(name);
            subject.add(ref.label() + "#" + Hashes.shortHash(ref.contentHash()));
        }
        boolean ready = checks.stream().filter(ReadinessReport.Check::blocking).allMatch(ReadinessReport.Check::passed);
        long advisoryFailures = checks.stream().filter(c -> !c.blocking() && !c.passed()).count();
        String recommendation = ready
                ? "All blocking checks passed across " + Checks.safe(plan.tasks()).size() + " planned tasks; " + advisoryFailures
                + " advisory item(s) for the approver to weigh. Approving this version approves " + subject.get(0) + "."
                : "Do not approve: blocking checks failed.";
        ReadinessReport report = new ReadinessReport(ready, List.copyOf(checks),
                Checks.safe(spec.ambiguities()).stream().map(a -> a.id() + ": " + a.assumption()).toList(), List.copyOf(subject), recommendation);
        return new AgentResult(AgentResult.Status.COMPLETED, recommendation,
                List.of(new AgentResult.Produced(Artifacts.READINESS_REPORT, report, ArtifactOrigin.DETERMINISTIC, null)),
                checks.stream().map(c -> Checks.evidence(c.id() + " " + c.description(), c.passed(), c.detail())).toList(),
                report.openAssumptions(), advisoryFailures == 0 ? List.of() : List.of(advisoryFailures + " advisory readiness check(s) did not pass."),
                List.of(Checks.decision("ready=" + ready + ".", "Derived only from blocking checks RR-1..RR-5."),
                        Checks.decision("Human approval required before promotion.", "Promotion is a high-impact action; the agent never promotes.")),
                null, null, AgentResult.Usage.NONE);
    }
}
