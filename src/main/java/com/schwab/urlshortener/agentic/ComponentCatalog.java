package com.schwab.urlshortener.agentic;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The orchestrator's knowledge of THIS codebase: a curated list of real
 * components (classpath resource agentic/component-catalog.json) with
 * their layer, responsibility, matching keywords, and existing tests.
 *
 * Curated rather than reflected from the classpath on purpose: a
 * responsibility line and keyword list are what make impact analysis
 * meaningful, and neither can be derived from a class name. The cost of
 * curation is drift, which ComponentCatalogTest guards against by failing
 * the build if any listed path or test file stops existing.
 */
@Component
public class ComponentCatalog {

    static final String RESOURCE = "agentic/component-catalog.json";

    /** Declaration order is also the dependency order the demo planner layers tasks in. */
    public enum Layer {
        DATA,
        SERVICE,
        CROSS_CUTTING,
        API,
        SECURITY,
        CONFIG
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CatalogComponent(String name, String path, Layer layer, String responsibility,
                                   List<String> keywords, List<String> tests) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record CatalogFile(List<CatalogComponent> components) {
    }

    private final Map<String, CatalogComponent> byName;
    private final List<CatalogComponent> ordered;

    @Autowired
    public ComponentCatalog(ObjectMapper objectMapper) {
        this(load(objectMapper));
    }

    ComponentCatalog(List<CatalogComponent> components) {
        Map<String, CatalogComponent> map = new LinkedHashMap<>();
        for (CatalogComponent c : components) {
            if (map.putIfAbsent(c.name(), c) != null) {
                throw new IllegalStateException("Duplicate component name in " + RESOURCE + ": " + c.name());
            }
        }
        this.byName = Map.copyOf(map);
        this.ordered = List.copyOf(components);
    }

    private static List<CatalogComponent> load(ObjectMapper objectMapper) {
        try (InputStream in = new ClassPathResource(RESOURCE).getInputStream()) {
            return objectMapper.readValue(in, CatalogFile.class).components();
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to load " + RESOURCE, e);
        }
    }

    public List<CatalogComponent> all() {
        return ordered;
    }

    public Optional<CatalogComponent> find(String name) {
        return Optional.ofNullable(name == null ? null : byName.get(name));
    }

    public boolean contains(String name) {
        return find(name).isPresent();
    }

    public int size() {
        return ordered.size();
    }
}
