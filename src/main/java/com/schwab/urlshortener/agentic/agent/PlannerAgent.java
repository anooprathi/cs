package com.schwab.urlshortener.agentic.agent;

import com.schwab.urlshortener.agentic.ComponentCatalog.Layer;
import com.schwab.urlshortener.agentic.model.ImpactAnalysis;
import com.schwab.urlshortener.agentic.model.ImpactAnalysis.ImpactedComponent;
import com.schwab.urlshortener.agentic.model.ImpactAnalysis.ProposedComponent;
import com.schwab.urlshortener.agentic.model.ImplementationPlan;
import com.schwab.urlshortener.agentic.model.ImplementationPlan.PlanTask;
import com.schwab.urlshortener.agentic.model.RequirementSpec.AcceptanceCriterion;
import com.schwab.urlshortener.agentic.model.SdlcStage;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * PLAN stage: a dependency-ordered task list.
 *
 * Deterministic rules: one task per impacted or proposed component,
 * grouped by layer (DATA → SERVICE → CROSS_CUTTING → API → SECURITY →
 * CONFIG); each task depends on every task in the nearest lower layer that
 * has any. Then three fixed closing tasks: tests (depends on all
 * implementation tasks, covers every acceptance criterion), documentation
 * (depends on tests), and full verification (depends on both). By
 * construction the graph is acyclic and every criterion is covered — the
 * quality gate still checks both, because in LLM mode nothing guarantees it.
 */
@Component
public class PlannerAgent implements SdlcAgent<ImplementationPlan> {

    private record WorkItem(Layer layer, String component, String description, List<String> criteria) {
    }

    private final PromptSupport prompts;

    public PlannerAgent(PromptSupport prompts) {
        this.prompts = prompts;
    }

    @Override
    public SdlcStage stage() {
        return SdlcStage.PLAN;
    }

    @Override
    public List<SdlcStage> dependsOn() {
        return List.of(SdlcStage.REQUIREMENTS, SdlcStage.IMPACT);
    }

    @Override
    public Class<ImplementationPlan> outputType() {
        return ImplementationPlan.class;
    }

    @Override
    public ImplementationPlan runDeterministic(AgentContext context) {
        ImpactAnalysis impact = context.impactAnalysis();
        List<String> allCriteria = context.requirementSpec().acceptanceCriteria().stream()
                .map(AcceptanceCriterion::id).toList();

        List<WorkItem> items = new ArrayList<>();
        for (ImpactedComponent ic : impact.impactedComponents()) {
            Layer layer = context.catalog().find(ic.component()).map(c -> c.layer()).orElse(Layer.SERVICE);
            items.add(new WorkItem(layer, ic.component(),
                    ic.changeType() + " " + ic.component() + ": " + ic.reason(), ic.relatedCriteria()));
        }
        for (ProposedComponent pc : impact.newComponents()) {
            items.add(new WorkItem(Layer.SERVICE, pc.name(),
                    "Create " + pc.name() + " in " + pc.packageName() + ": " + pc.responsibility(), allCriteria));
        }
        items.sort(Comparator.comparing(WorkItem::layer));

        Map<Layer, List<String>> taskIdsByLayer = new TreeMap<>();
        List<PlanTask> tasks = new ArrayList<>();
        for (WorkItem item : items) {
            String id = "T-" + (tasks.size() + 1);
            List<String> deps = taskIdsByLayer.entrySet().stream()
                    .filter(e -> e.getKey().compareTo(item.layer()) < 0)
                    .reduce((first, second) -> second)
                    .map(Map.Entry::getValue)
                    .map(List::copyOf)
                    .orElse(List.of());
            tasks.add(new PlanTask(id, "Change " + item.component(), item.description(), deps,
                    List.of(item.component()), item.criteria()));
            taskIdsByLayer.computeIfAbsent(item.layer(), k -> new ArrayList<>()).add(id);
        }

        List<String> implementationIds = tasks.stream().map(PlanTask::id).toList();
        List<String> changedComponents = items.stream().map(WorkItem::component).toList();

        String testId = "T-" + (tasks.size() + 1);
        tasks.add(new PlanTask(testId, "Write and extend automated tests",
                "Add unit, slice, and integration tests per the TESTS artifact so every acceptance criterion has "
                        + "executable proof; keep the existing suite green.",
                implementationIds, changedComponents, allCriteria));

        String docsId = "T-" + (tasks.size() + 1);
        tasks.add(new PlanTask(docsId, "Update documentation",
                "Update README.md (endpoints, curl walkthrough), ARCHITECTURE.md (design decisions) and "
                        + "ENGINEERING_SUMMARY.md (what was generated, edited, rejected, and why).",
                List.of(testId), List.of(), List.of()));

        tasks.add(new PlanTask("T-" + (tasks.size() + 1), "Verify end to end",
                "Run mvn clean verify (all tests plus the 80% JaCoCo line-coverage gate) and the Postman "
                        + "collection against a freshly started dev instance; record results.",
                List.of(testId, docsId), List.of(), List.of()));

        return new ImplementationPlan(tasks);
    }

    @Override
    public String systemPrompt() {
        return prompts.system("""
                Role: technical planner.
                Produce an implementation plan of small, independently reviewable tasks (T-n). Each task \
                lists the tasks it depends on (no cycles, only existing ids), the components it changes \
                (catalog names or proposed new component names from the impact analysis), and the AC-n ids \
                it contributes to. Every acceptance criterion must be covered by at least one task. Include \
                testing, documentation, and a final verification task (mvn clean verify with the 80% JaCoCo gate).
                """);
    }

    @Override
    public String userPrompt(AgentContext context) {
        return prompts.requirementBlock(context)
                + prompts.artifactBlock("Requirement spec", context.requirementSpec())
                + prompts.artifactBlock("Impact analysis", context.impactAnalysis());
    }
}
