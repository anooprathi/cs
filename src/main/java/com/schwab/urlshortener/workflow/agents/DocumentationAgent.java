package com.schwab.urlshortener.workflow.agents;

import com.schwab.urlshortener.agentic.model.ExecutionMode;
import com.schwab.urlshortener.agentic.model.ImpactAnalysis;
import com.schwab.urlshortener.agentic.model.ImplementationPlan;
import com.schwab.urlshortener.agentic.model.RequirementSpec;
import com.schwab.urlshortener.workflow.artifact.DocumentationBundle;
import com.schwab.urlshortener.workflow.engine.AgentRequest;
import com.schwab.urlshortener.workflow.engine.AgentResult;
import com.schwab.urlshortener.workflow.engine.ArtifactOrigin;
import com.schwab.urlshortener.workflow.engine.GateCriterion;
import com.schwab.urlshortener.workflow.engine.GateCriterion.Result;
import com.schwab.urlshortener.workflow.engine.GateInput;
import com.schwab.urlshortener.workflow.engine.StageAgent;
import com.schwab.urlshortener.workflow.engine.ToolPolicy;
import com.schwab.urlshortener.workflow.graph.WorkflowStage;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * DOCUMENTATION stage agent: release notes plus a criterion → requirement →
 * task → component traceability matrix, assembled deterministically from
 * approved upstream artifacts. Deterministic in every mode, because
 * documentation is a projection of reviewed artifacts, not new content.
 */
@Component
public class DocumentationAgent implements StageAgent {

    @Override
    public String key() {
        return "documentation";
    }

    @Override
    public String version() {
        return "2";
    }

    @Override
    public WorkflowStage stage() {
        return WorkflowStage.DOCUMENTATION;
    }

    @Override
    public String description() {
        return "Assembles release notes and a requirement → criterion → task → component traceability matrix.";
    }

    @Override
    public String intent() {
        return "Document what changes and why, traceably, for reviewers and release notes.";
    }

    @Override
    public List<String> constraints() {
        return List.of("Every acceptance criterion must appear in the traceability matrix.",
                "State how the upstream analysis was produced (execution mode).");
    }

    @Override
    public List<String> consumes() {
        return List.of(Artifacts.REQUIREMENT_SPEC, Artifacts.ARCHITECTURE_DESIGN, Artifacts.IMPLEMENTATION_PLAN);
    }

    @Override
    public List<String> produces() {
        return List.of(Artifacts.DOCUMENTATION);
    }

    @Override
    public ToolPolicy toolPolicy() {
        return ToolPolicy.of(ToolPolicy.Tool.READ_ARTIFACTS);
    }

    @Override
    public List<GateCriterion> entryCriteria() {
        return List.of(new GateCriterion("DOC-ENTRY-1", "Upstream implementation-plan has at least one task",
                in -> Result.of(!Checks.safe(in.input(Artifacts.IMPLEMENTATION_PLAN, ImplementationPlan.class).tasks()).isEmpty(),
                        "plan present", "plan has no tasks")));
    }

    @Override
    public List<GateCriterion> exitCriteria() {
        return List.of(
                new GateCriterion("DOC-EXIT-1", "The traceability matrix covers every acceptance criterion",
                        in -> {
                            Set<String> traced = new HashSet<>();
                            doc(in).traceability().forEach(r -> traced.add(r.criterionId()));
                            List<String> missing = Checks.criterionIds(in.input(Artifacts.REQUIREMENT_SPEC, RequirementSpec.class))
                                    .stream().filter(id -> !traced.contains(id)).toList();
                            return Result.of(missing.isEmpty(), "all traced", "untraced " + missing);
                        }),
                new GateCriterion("DOC-EXIT-2", "Every traced criterion maps to at least one implementation task",
                        in -> {
                            List<String> orphan = doc(in).traceability().stream().filter(r -> r.tasks().isEmpty())
                                    .map(DocumentationBundle.TraceRow::criterionId).toList();
                            return Result.of(orphan.isEmpty(), "all mapped", "no task for " + orphan);
                        }),
                new GateCriterion("DOC-EXIT-3", "The documentation discloses how the analysis was produced",
                        in -> Result.of(doc(in).executionDisclosure() != null && !doc(in).executionDisclosure().isBlank(),
                                "disclosed", "missing execution disclosure")));
    }

    private static DocumentationBundle doc(GateInput in) {
        return in.output(Artifacts.DOCUMENTATION, DocumentationBundle.class);
    }

    @Override
    public AgentResult execute(AgentRequest request) {
        RequirementSpec spec = request.input(Artifacts.REQUIREMENT_SPEC, RequirementSpec.class);
        ImpactAnalysis design = request.input(Artifacts.ARCHITECTURE_DESIGN, ImpactAnalysis.class);
        ImplementationPlan plan = request.input(Artifacts.IMPLEMENTATION_PLAN, ImplementationPlan.class);

        Map<String, String> statementText = new LinkedHashMap<>();
        Stream.concat(Checks.safe(spec.functionalRequirements()).stream(), Checks.safe(spec.nonFunctionalRequirements()).stream())
                .forEach(s -> statementText.put(s.id(), s.text()));
        List<DocumentationBundle.TraceRow> rows = Checks.safe(spec.acceptanceCriteria()).stream().map(ac -> {
            List<ImplementationPlan.PlanTask> tasks = Checks.safe(plan.tasks()).stream()
                    .filter(t -> Checks.safe(t.acceptanceCriteria()).contains(ac.id())).toList();
            return new DocumentationBundle.TraceRow(ac.id(), ac.sourceRequirementId(),
                    statementText.getOrDefault(ac.sourceRequirementId(), ac.then()),
                    tasks.stream().map(ImplementationPlan.PlanTask::id).toList(),
                    tasks.stream().flatMap(t -> Checks.safe(t.components()).stream()).distinct().toList());
        }).toList();
        List<DocumentationBundle.ComponentNote> components = Checks.safe(design.impactedComponents()).stream()
                .map(c -> new DocumentationBundle.ComponentNote(c.component(), String.valueOf(c.changeType()), c.reason()))
                .toList();
        String disclosure = request.mode() == ExecutionMode.LLM
                ? "Requirement analysis, design, plan, patch, tests, and risks were generated by a language model and checked by "
                + "deterministic gates; review before acting."
                : "Requirement analysis, design, plan, patch, tests, and risks were produced by rule-based demo logic "
                + "(DETERMINISTIC_DEMO); no language model was called.";
        DocumentationBundle bundle = new DocumentationBundle(request.title(), disclosure, spec.summary(), rows, components,
                Checks.safe(spec.ambiguities()).stream().map(a -> a.id() + ": " + a.assumption()).toList(), Checks.safe(spec.outOfScope()));
        return new AgentResult(AgentResult.Status.COMPLETED,
                "Documented " + components.size() + " component changes and traced " + rows.size() + " acceptance criteria.",
                List.of(new AgentResult.Produced(Artifacts.DOCUMENTATION, bundle, ArtifactOrigin.DETERMINISTIC, null)),
                List.of(Checks.evidence("traceability rows", rows.size() == Checks.criterionIds(spec).size(), rows.size() + " rows")),
                bundle.assumptions(), List.of(),
                List.of(Checks.decision("Deterministic documentation assembly.",
                        "Documentation projects reviewed artifacts; generating it with a model would add unreviewed content.")),
                null, null, AgentResult.Usage.NONE);
    }
}
