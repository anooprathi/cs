package com.schwab.urlshortener.workflow.engine;

import com.schwab.urlshortener.workflow.graph.AgentSpecs;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** All {@link StageAgent} beans by key. A duplicate key fails application startup. */
@Component
public class AgentRegistry implements AgentSpecs {

    private final Map<String, StageAgent> byKey;

    public AgentRegistry(List<StageAgent> executors) {
        Map<String, StageAgent> map = new LinkedHashMap<>();
        for (StageAgent e : executors) {
            if (map.putIfAbsent(e.key(), e) != null) {
                throw new IllegalStateException("Two stage agents registered with key '" + e.key() + "'");
            }
        }
        this.byKey = Map.copyOf(map);
    }

    public Optional<StageAgent> find(String key) {
        return Optional.ofNullable(key == null ? null : byKey.get(key));
    }

    public StageAgent get(String key) {
        return find(key).orElseThrow(() -> new IllegalStateException("Unknown agent '" + key + "'"));
    }

    public Collection<StageAgent> all() {
        return byKey.values();
    }

    @Override
    public Optional<AgentSpec> spec(String agentKey) {
        return find(agentKey).map(e -> new AgentSpec(e.key(), e.stage(), e.consumes(), e.produces()));
    }
}
