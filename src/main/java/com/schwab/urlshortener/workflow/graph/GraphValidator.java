package com.schwab.urlshortener.workflow.graph;

import com.schwab.urlshortener.workflow.graph.GraphValidationResult.ErrorCode;
import com.schwab.urlshortener.workflow.graph.GraphValidationResult.GraphError;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Validates a workflow graph before anything runs. A run can only be created
 * from a definition that passes, and the engine re-validates the snapshot at
 * every drive.
 *
 * Structural checks: non-empty, unique non-blank keys, known agents, no
 * self, unknown, or duplicate dependencies, and no cycles (the first cycle is
 * reported as a path).
 *
 * Data-flow checks, which catch a graph that is a valid DAG but still wrong:
 * every artifact has at most one producer, every consumed artifact is produced
 * by some task, and that producer must be an ANCESTOR of the consumer. A
 * producer that merely exists elsewhere in the graph would race the consumer.
 * Its output might not exist yet when the consumer starts, so a missing edge
 * is an invalid dependency, not a scheduling detail.
 */
@Component
public class GraphValidator {

    public GraphValidationResult validate(WorkflowDefinition definition, AgentSpecs specs) {
        List<GraphError> errors = new ArrayList<>();
        List<TaskNode> nodes = definition == null ? List.of() : definition.tasks();
        if (nodes.isEmpty()) {
            errors.add(new GraphError(ErrorCode.EMPTY_GRAPH, null, "The workflow has no tasks."));
            return invalid(errors);
        }

        Map<String, TaskNode> byKey = new LinkedHashMap<>();
        for (TaskNode n : nodes) {
            if (n.key() == null || n.key().isBlank()) {
                errors.add(new GraphError(ErrorCode.BLANK_TASK_KEY, null, "A task has a blank key."));
            } else if (byKey.putIfAbsent(n.key(), n) != null) {
                errors.add(new GraphError(ErrorCode.DUPLICATE_TASK, n.key(), "Task key '" + n.key() + "' is used more than once."));
            }
        }

        Map<String, AgentSpecs.AgentSpec> specByTask = new LinkedHashMap<>();
        for (TaskNode n : byKey.values()) {
            Optional<AgentSpecs.AgentSpec> spec = n.agent() == null ? Optional.empty() : specs.spec(n.agent());
            if (spec.isEmpty()) {
                errors.add(new GraphError(ErrorCode.UNKNOWN_AGENT, n.key(), "Task '" + n.key() + "' uses unknown agent '" + n.agent() + "'."));
            } else {
                specByTask.put(n.key(), spec.get());
            }
            Set<String> seen = new HashSet<>();
            for (String dep : n.dependsOn()) {
                if (n.key().equals(dep)) {
                    errors.add(new GraphError(ErrorCode.SELF_DEPENDENCY, n.key(), "Task '" + n.key() + "' depends on itself."));
                } else if (!byKey.containsKey(dep)) {
                    errors.add(new GraphError(ErrorCode.UNKNOWN_DEPENDENCY, n.key(), "Task '" + n.key() + "' depends on unknown task '" + dep + "'."));
                }
                if (!seen.add(dep)) {
                    errors.add(new GraphError(ErrorCode.DUPLICATE_DEPENDENCY, n.key(), "Task '" + n.key() + "' lists dependency '" + dep + "' twice."));
                }
            }
        }

        Optional<List<String>> cycle = findCycle(byKey);
        cycle.ifPresent(path -> errors.add(new GraphError(ErrorCode.CYCLE, path.get(0),
                "Dependency cycle: " + String.join(" -> ", path) + ".")));
        if (cycle.isPresent() || hasStructuralError(errors)) {
            return invalid(errors);
        }

        // --- data flow: single producer, inputs produced upstream ---
        Map<String, String> producers = new LinkedHashMap<>();
        for (Map.Entry<String, AgentSpecs.AgentSpec> e : specByTask.entrySet()) {
            for (String artifact : e.getValue().produces()) {
                String previous = producers.putIfAbsent(artifact, e.getKey());
                if (previous != null) {
                    errors.add(new GraphError(ErrorCode.DUPLICATE_PRODUCER, e.getKey(), "Artifact '" + artifact
                            + "' is produced by both '" + previous + "' and '" + e.getKey() + "'."));
                }
            }
        }
        Map<String, Set<String>> ancestors = ancestors(byKey);
        for (Map.Entry<String, AgentSpecs.AgentSpec> e : specByTask.entrySet()) {
            String task = e.getKey();
            for (String input : e.getValue().consumes()) {
                String producer = producers.get(input);
                if (producer == null) {
                    errors.add(new GraphError(ErrorCode.MISSING_INPUT, task, "Task '" + task + "' consumes '" + input
                            + "', which no task in this graph produces."));
                } else if (!ancestors.get(task).contains(producer)) {
                    errors.add(new GraphError(ErrorCode.INPUT_NOT_UPSTREAM, task, "Task '" + task + "' consumes '" + input
                            + "' from '" + producer + "', which is not upstream of it. Add a dependency path from '"
                            + producer + "' to '" + task + "'."));
                }
            }
        }
        if (!errors.isEmpty()) {
            return invalid(errors);
        }

        List<List<String>> layers = layers(byKey);
        List<String> order = layers.stream().flatMap(List::stream).toList();
        return new GraphValidationResult(true, List.of(), order, layers, Map.copyOf(producers));
    }

    private static boolean hasStructuralError(List<GraphError> errors) {
        return errors.stream().anyMatch(e -> e.code() == ErrorCode.UNKNOWN_DEPENDENCY || e.code() == ErrorCode.SELF_DEPENDENCY
                || e.code() == ErrorCode.UNKNOWN_AGENT || e.code() == ErrorCode.DUPLICATE_TASK || e.code() == ErrorCode.BLANK_TASK_KEY
                || e.code() == ErrorCode.DUPLICATE_DEPENDENCY);
    }

    private static GraphValidationResult invalid(List<GraphError> errors) {
        return new GraphValidationResult(false, List.copyOf(errors), List.of(), List.of(), Map.of());
    }

    /** Kahn layering: layer N holds tasks whose dependencies all lie in layers < N. Within a layer, definition order is kept. */
    static List<List<String>> layers(Map<String, TaskNode> byKey) {
        List<List<String>> layers = new ArrayList<>();
        Set<String> placed = new HashSet<>();
        while (placed.size() < byKey.size()) {
            List<String> layer = byKey.values().stream()
                    .filter(n -> !placed.contains(n.key()))
                    .filter(n -> placed.containsAll(n.dependsOn()))
                    .map(TaskNode::key)
                    .toList();
            if (layer.isEmpty()) {
                throw new IllegalStateException("layers() called on a cyclic graph");
            }
            placed.addAll(layer);
            layers.add(layer);
        }
        return layers;
    }

    /** Transitive dependencies of every task (assumes an acyclic graph with known dependencies). */
    public static Map<String, Set<String>> ancestors(Map<String, TaskNode> byKey) {
        Map<String, Set<String>> memo = new HashMap<>();
        for (String key : byKey.keySet()) {
            collectAncestors(key, byKey, memo);
        }
        return memo;
    }

    private static Set<String> collectAncestors(String key, Map<String, TaskNode> byKey, Map<String, Set<String>> memo) {
        Set<String> cached = memo.get(key);
        if (cached != null) {
            return cached;
        }
        Set<String> result = new LinkedHashSet<>();
        for (String dep : byKey.get(key).dependsOn()) {
            result.add(dep);
            result.addAll(collectAncestors(dep, byKey, memo));
        }
        memo.put(key, result);
        return result;
    }

    /** Transitive dependents of every task. */
    public static Map<String, Set<String>> descendants(Map<String, TaskNode> byKey) {
        Map<String, Set<String>> result = new HashMap<>();
        byKey.keySet().forEach(k -> result.put(k, new LinkedHashSet<>()));
        ancestors(byKey).forEach((task, ancestorSet) -> ancestorSet.forEach(a -> result.get(a).add(task)));
        return result;
    }

    /** Iterative three-colour DFS; ignores edges to unknown tasks (reported separately). */
    static Optional<List<String>> findCycle(Map<String, TaskNode> byKey) {
        Map<String, Integer> state = new HashMap<>(); // 1 = on stack, 2 = done
        for (Map.Entry<String, TaskNode> entry : byKey.entrySet()) {
            String start = entry.getKey();
            if (state.containsKey(start)) {
                continue;
            }
            Deque<String> path = new ArrayDeque<>();
            Deque<Iterator<String>> iterators = new ArrayDeque<>();
            path.push(start);
            iterators.push(entry.getValue().dependsOn().iterator());
            state.put(start, 1);
            while (!path.isEmpty()) {
                Iterator<String> it = iterators.peek();
                if (it.hasNext()) {
                    String next = it.next();
                    if (!byKey.containsKey(next)) {
                        continue;
                    }
                    Integer s = state.get(next);
                    if (s == null) {
                        state.put(next, 1);
                        path.push(next);
                        iterators.push(byKey.get(next).dependsOn().iterator());
                    } else if (s == 1) {
                        List<String> stack = new ArrayList<>(path);
                        Collections.reverse(stack);
                        List<String> cycle = new ArrayList<>(stack.subList(stack.indexOf(next), stack.size()));
                        cycle.add(next);
                        return Optional.of(cycle);
                    }
                } else {
                    state.put(path.pop(), 2);
                    iterators.pop();
                }
            }
        }
        return Optional.empty();
    }
}
