package com.schwab.urlshortener.workflow.agents;

import com.schwab.urlshortener.agentic.model.RequirementSpec;
import com.schwab.urlshortener.workflow.adapter.Generation;
import com.schwab.urlshortener.workflow.engine.AgentResult;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Small null-safe helpers shared by the stage agents and their gate criteria. */
final class Checks {

    private Checks() {
    }

    static <T> List<T> safe(List<T> list) {
        return list == null ? List.of() : list;
    }

    static Set<String> criterionIds(RequirementSpec spec) {
        return safe(spec.acceptanceCriteria()).stream().map(RequirementSpec.AcceptanceCriterion::id)
                .filter(Objects::nonNull).collect(Collectors.toCollection(LinkedHashSet::new));
    }

    static <T> List<String> duplicates(List<T> items, Function<T, String> id) {
        return safe(items).stream().map(id).filter(Objects::nonNull)
                .collect(Collectors.groupingBy(x -> x, LinkedHashMap::new, Collectors.counting()))
                .entrySet().stream().filter(e -> e.getValue() > 1).map(Map.Entry::getKey).toList();
    }

    static String listOrNone(Collection<String> values) {
        return values.isEmpty() ? "none" : String.join(", ", values);
    }

    static String producedBy(Generation<?> g) {
        return g.adapter() + (g.model() == null ? "" : " (" + g.model() + ")");
    }

    static AgentResult.Decision decision(String decision, String rationale) {
        return new AgentResult.Decision(decision, rationale);
    }

    static AgentResult.Evidence evidence(String check, boolean passed, String detail) {
        return new AgentResult.Evidence(check, passed, detail);
    }
}
