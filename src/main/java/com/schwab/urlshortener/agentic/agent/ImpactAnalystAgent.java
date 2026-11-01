package com.schwab.urlshortener.agentic.agent;

import com.schwab.urlshortener.agentic.ComponentCatalog;
import com.schwab.urlshortener.agentic.ComponentCatalog.CatalogComponent;
import com.schwab.urlshortener.agentic.ComponentCatalog.Layer;
import com.schwab.urlshortener.agentic.model.ImpactAnalysis;
import com.schwab.urlshortener.agentic.model.ImpactAnalysis.ChangeType;
import com.schwab.urlshortener.agentic.model.ImpactAnalysis.ImpactedComponent;
import com.schwab.urlshortener.agentic.model.ImpactAnalysis.ProposedComponent;
import com.schwab.urlshortener.agentic.model.RequirementSpec;
import com.schwab.urlshortener.agentic.model.RequirementSpec.AcceptanceCriterion;
import com.schwab.urlshortener.agentic.model.SdlcStage;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * IMPACT stage: which real components of this codebase the requirement
 * touches.
 *
 * Deterministic rules: each catalog component is scored by how many of its
 * keywords appear (word-start match) in the requirement text; components
 * scoring at least 1 are kept, best MAX_COMPONENTS by score (ties broken by
 * catalog order), then ordered by layer. A component's related acceptance
 * criteria are the ones whose text also matches one of its keywords. If
 * nothing matches at all, a new service component is proposed instead of
 * pretending an existing one is affected.
 */
@Component
public class ImpactAnalystAgent implements SdlcAgent<ImpactAnalysis> {

    static final int MAX_COMPONENTS = 8;

    private static final Map<Layer, ChangeType> CHANGE_TYPE_BY_LAYER = Map.of(
            Layer.DATA, ChangeType.MODIFY,
            Layer.SERVICE, ChangeType.MODIFY,
            Layer.CROSS_CUTTING, ChangeType.EXTEND,
            Layer.API, ChangeType.EXTEND,
            Layer.SECURITY, ChangeType.MODIFY,
            Layer.CONFIG, ChangeType.CONFIGURE);

    /** Areas worth explicitly calling out as unaffected when none of their components matched. */
    private static final Map<String, List<String>> NOTABLE_AREAS = Map.of(
            "Public redirect hot path", List.of("RedirectController", "CachedShortCodeLookup"),
            "Billing and invoicing", List.of("BillingService", "InvoiceService", "UsageMeteringService", "InvoicePdfGenerator"),
            "Authentication and authorization filter chain", List.of("SecurityConfig", "ApiKeyAuthenticationFilter"),
            "Per-tenant rate limiting", List.of("TenantRateLimiterService", "RateLimitFilter"));

    private final PromptSupport prompts;

    public ImpactAnalystAgent(PromptSupport prompts) {
        this.prompts = prompts;
    }

    @Override
    public SdlcStage stage() {
        return SdlcStage.IMPACT;
    }

    @Override
    public List<SdlcStage> dependsOn() {
        return List.of(SdlcStage.REQUIREMENTS);
    }

    @Override
    public Class<ImpactAnalysis> outputType() {
        return ImpactAnalysis.class;
    }

    private record Scored(int catalogIndex, CatalogComponent component, List<String> matched) {
    }

    @Override
    public ImpactAnalysis runDeterministic(AgentContext context) {
        RequirementSpec spec = context.requirementSpec();
        ComponentCatalog catalog = context.catalog();
        String text = TextSupport.normalize(context.title() + "\n" + context.requirement());

        List<Scored> scored = new ArrayList<>();
        List<CatalogComponent> all = catalog.all();
        for (int i = 0; i < all.size(); i++) {
            CatalogComponent c = all.get(i);
            List<String> matched = TextSupport.matchedKeywords(text, c.keywords());
            if (!matched.isEmpty()) {
                scored.add(new Scored(i, c, matched));
            }
        }
        List<Scored> selected = scored.stream()
                .sorted(Comparator.comparingInt((Scored s) -> -s.matched().size()).thenComparingInt(Scored::catalogIndex))
                .limit(MAX_COMPONENTS)
                .sorted(Comparator.comparing((Scored s) -> s.component().layer()).thenComparingInt(Scored::catalogIndex))
                .toList();

        List<ImpactedComponent> impacted = new ArrayList<>();
        for (Scored s : selected) {
            CatalogComponent c = s.component();
            List<String> related = spec.acceptanceCriteria().stream()
                    .filter(ac -> !TextSupport.matchedKeywords(TextSupport.normalize(criterionText(ac)), c.keywords()).isEmpty())
                    .map(AcceptanceCriterion::id)
                    .toList();
            impacted.add(new ImpactedComponent(c.name(), CHANGE_TYPE_BY_LAYER.get(c.layer()),
                    c.responsibility() + " Matched requirement keywords: " + s.matched() + ".", related));
        }

        List<ProposedComponent> proposed = new ArrayList<>();
        if (impacted.isEmpty()) {
            proposed.add(new ProposedComponent(toPascalCase(context.title()) + "Service",
                    "com.schwab.urlshortener." + toPackageName(context.title()),
                    "New capability with no matching existing component; owns the logic for: " + spec.summary()));
        }

        List<String> impactedNames = impacted.stream().map(ImpactedComponent::component).toList();
        List<String> unaffected = NOTABLE_AREAS.entrySet().stream()
                .filter(area -> area.getValue().stream().noneMatch(impactedNames::contains))
                .map(area -> area.getKey() + ": no requirement keyword matched its components " + area.getValue() + ".")
                .sorted()
                .toList();

        return new ImpactAnalysis(impacted, proposed, unaffected);
    }

    /**
     * Only the "then" clause — it carries the requirement's own wording; the
     * given/when clauses are template boilerplate (e.g. "X-API-Key ... management
     * API") that would otherwise relate every criterion to the auth components.
     */
    static String criterionText(AcceptanceCriterion ac) {
        return ac.then();
    }

    static String toPascalCase(String title) {
        StringBuilder sb = new StringBuilder();
        for (String word : title.replaceAll("[^A-Za-z0-9 ]", " ").trim().split("\\s+")) {
            if (!word.isEmpty()) {
                sb.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1).toLowerCase());
            }
        }
        return sb.isEmpty() ? "NewFeature" : sb.toString();
    }

    private static String toPackageName(String title) {
        String pkg = title.toLowerCase().replaceAll("[^a-z0-9]", "");
        return pkg.isEmpty() || Character.isDigit(pkg.charAt(0)) ? "feature" : pkg;
    }

    @Override
    public String systemPrompt() {
        return prompts.system("""
                Role: codebase impact analyst.
                Using ONLY the component catalog provided, list the existing components the requirement \
                would change, with change type, a concrete reason, and the AC-n ids each change serves. \
                Use the catalog names exactly; a name not in the catalog will be rejected by the quality gate. \
                If something genuinely new is needed, list it under newComponents instead. \
                Name notable areas that stay unaffected.
                """);
    }

    @Override
    public String userPrompt(AgentContext context) {
        return prompts.requirementBlock(context)
                + prompts.catalogBlock(context.catalog())
                + prompts.artifactBlock("Requirement spec", context.requirementSpec());
    }
}
