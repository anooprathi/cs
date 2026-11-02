package com.schwab.urlshortener.workflow.agents;

import com.schwab.urlshortener.agentic.agent.RiskReviewerAgent;
import com.schwab.urlshortener.agentic.model.ImpactAnalysis;
import com.schwab.urlshortener.agentic.model.ImplementationPlan;
import com.schwab.urlshortener.agentic.model.RequirementSpec;
import com.schwab.urlshortener.agentic.model.RiskAssessment;
import com.schwab.urlshortener.workflow.adapter.Generation;
import com.schwab.urlshortener.workflow.artifact.PolicyReport;
import com.schwab.urlshortener.workflow.engine.AgentRequest;
import com.schwab.urlshortener.workflow.engine.AgentResult;
import com.schwab.urlshortener.workflow.engine.ArtifactOrigin;
import com.schwab.urlshortener.workflow.engine.GateCriterion;
import com.schwab.urlshortener.workflow.engine.GateCriterion.Result;
import com.schwab.urlshortener.workflow.engine.StageAgent;
import com.schwab.urlshortener.workflow.engine.ToolPolicy;
import com.schwab.urlshortener.workflow.graph.WorkflowStage;
import com.schwab.urlshortener.workflow.workspace.ChangePatch;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * SECURITY_POLICY_VALIDATION stage agent: a risk assessment (demo or LLM) plus
 * the deterministic {@link PolicyEngine} over the requirement, every upstream
 * artifact, and the patch diff. A BLOCKER violation fails the exit gate. Policy
 * failures are never retried and never fall back.
 *
 * It reads the raw requirement (secret and scope scanning must see the text as
 * submitted), so it re-runs whenever the requirement changes, even when the spec
 * doesn't.
 */
@Component
public class SecurityPolicyAgent implements StageAgent {

    private final RiskReviewerAgent reviewer;
    private final AgentStageRunner runner;
    private final PolicyEngine policyEngine;

    public SecurityPolicyAgent(RiskReviewerAgent reviewer, AgentStageRunner runner, PolicyEngine policyEngine) {
        this.reviewer = reviewer;
        this.runner = runner;
        this.policyEngine = policyEngine;
    }

    @Override
    public String key() {
        return "security-policy";
    }

    @Override
    public String version() {
        return "2";
    }

    @Override
    public WorkflowStage stage() {
        return WorkflowStage.SECURITY_POLICY_VALIDATION;
    }

    @Override
    public String description() {
        return "Assesses risks, then checks deterministic security and policy rules " + PolicyEngine.RULES + " over the requirement, "
                + "artifacts, and patch.";
    }

    @Override
    public String intent() {
        return "Find security, data-handling, and change-control problems before release, and block on policy violations.";
    }

    @Override
    public List<String> constraints() {
        return List.of("Every HIGH risk needs a concrete mitigation.", "Policy rules are deterministic and cannot be waived by the agent.");
    }

    @Override
    public List<String> consumes() {
        return List.of(Artifacts.REQUIREMENT_SPEC, Artifacts.ARCHITECTURE_DESIGN, Artifacts.IMPLEMENTATION_PLAN, Artifacts.CHANGE_PATCH);
    }

    @Override
    public List<String> produces() {
        return List.of(Artifacts.RISK_ASSESSMENT, Artifacts.POLICY_REPORT);
    }

    @Override
    public boolean readsRequirement() {
        return true;
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
        return List.of(new GateCriterion("SEC-ENTRY-1", "Upstream design is available to assess",
                in -> Result.of(in.input(Artifacts.ARCHITECTURE_DESIGN, ImpactAnalysis.class) != null, "available", "missing design")));
    }

    @Override
    public List<GateCriterion> exitCriteria() {
        return List.of(
                new GateCriterion("SEC-EXIT-1", "Every HIGH risk has a mitigation",
                        in -> {
                            List<String> missing = Checks.safe(in.output(Artifacts.RISK_ASSESSMENT, RiskAssessment.class).risks()).stream()
                                    .filter(r -> r.severity() == RiskAssessment.Severity.HIGH && (r.mitigation() == null || r.mitigation().isBlank()))
                                    .map(RiskAssessment.Risk::id).toList();
                            return Result.of(missing.isEmpty(), "all mitigated", "unmitigated HIGH risks " + missing);
                        }),
                new GateCriterion("SEC-EXIT-2", "No BLOCKER security or policy violation",
                        in -> {
                            PolicyReport p = in.output(Artifacts.POLICY_REPORT, PolicyReport.class);
                            List<String> blockers = p.violations().stream().filter(v -> v.severity() == PolicyReport.Severity.BLOCKER)
                                    .map(v -> v.ruleId() + " at " + v.location()).toList();
                            return Result.of(blockers.isEmpty(), p.warnings() + " warning(s), no blockers", "blockers " + blockers);
                        }));
    }

    @Override
    public AgentResult execute(AgentRequest request) {
        Generation<RiskAssessment> g = runner.run(reviewer, request);
        PolicyReport report = policyEngine.evaluate(request.requirement(),
                request.input(Artifacts.REQUIREMENT_SPEC, RequirementSpec.class),
                request.input(Artifacts.ARCHITECTURE_DESIGN, ImpactAnalysis.class),
                request.input(Artifacts.IMPLEMENTATION_PLAN, ImplementationPlan.class),
                g.output(),
                request.input(Artifacts.CHANGE_PATCH, ChangePatch.class));
        return new AgentResult(AgentResult.Status.COMPLETED,
                "Assessed " + Checks.safe(g.output().risks()).size() + " risks; policy check found " + report.blockers()
                        + " blocker(s) and " + report.warnings() + " warning(s).",
                List.of(new AgentResult.Produced(Artifacts.RISK_ASSESSMENT, g.output(), g.origin(), g.model()),
                        new AgentResult.Produced(Artifacts.POLICY_REPORT, report, ArtifactOrigin.DETERMINISTIC, null)),
                report.violations().stream().map(v -> Checks.evidence(v.ruleId() + " @ " + v.location(),
                        v.severity() != PolicyReport.Severity.BLOCKER, v.message())).toList(),
                List.of(), Checks.safe(g.output().risks()).stream().map(r -> r.id() + " " + r.severity() + ": " + r.description()).toList(),
                List.of(Checks.decision("Evaluated rules " + PolicyEngine.RULES + ".",
                                "Rules are configured in app.workflow.policy.* and are deterministic in every mode."),
                        Checks.decision("Risks by " + Checks.producedBy(g) + ".", "Execution mode of the run.")),
                null, null, g.usage());
    }
}
