package com.schwab.urlshortener.workflow.engine;

import java.util.Map;

/**
 * What a gate criterion can look at. Entry criteria see the task's inputs.
 * Exit criteria see the inputs plus the artifacts the task just produced.
 */
public final class GateInput {

    private final AgentRequest context;
    private final Map<String, Object> outputs;

    private GateInput(AgentRequest context, Map<String, Object> outputs) {
        this.context = context;
        this.outputs = outputs;
    }

    public static GateInput entry(AgentRequest context) {
        return new GateInput(context, Map.of());
    }

    public static GateInput exit(AgentRequest context, Map<String, Object> outputs) {
        return new GateInput(context, Map.copyOf(outputs));
    }

    public AgentRequest context() {
        return context;
    }

    /** Gate reads are not recorded as lineage (see AgentRequest#peek). */
    public <T> T input(String name, Class<T> type) {
        return context.peek(name, type);
    }

    public <T> T output(String name, Class<T> type) {
        Object value = outputs.get(name);
        if (value == null) {
            throw new IllegalStateException("No output artifact '" + name + "' to check");
        }
        return type.cast(value);
    }
}
