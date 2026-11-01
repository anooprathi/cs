package com.schwab.urlshortener.agentic.agent;

import com.schwab.urlshortener.agentic.ComponentCatalog.Layer;
import com.schwab.urlshortener.agentic.model.ImpactAnalysis;
import com.schwab.urlshortener.agentic.model.ImpactAnalysis.ImpactedComponent;
import com.schwab.urlshortener.agentic.model.RequirementSpec;
import com.schwab.urlshortener.agentic.model.RequirementSpec.Ambiguity;
import com.schwab.urlshortener.agentic.model.RiskAssessment;
import com.schwab.urlshortener.agentic.model.RiskAssessment.Risk;
import com.schwab.urlshortener.agentic.model.RiskAssessment.Severity;
import com.schwab.urlshortener.agentic.model.SdlcStage;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * RISKS stage: what could go wrong and how to contain it.
 *
 * Deterministic rules are a fixed table of risks this specific codebase is
 * known to carry, each triggered by which components are impacted — the
 * same risks the repo's own docs already name (denyAll catchall, ddl-auto
 * with no migrations, integer-cents billing, the redirect hot path,
 * fail-open safety checks). Open ambiguities add one MEDIUM risk, and a
 * LOW coverage-regression risk is always present. Each rule fires at most
 * once per run, listing every component that triggered it.
 */
@Component
public class RiskReviewerAgent implements SdlcAgent<RiskAssessment> {

    private record Rule(String description, Severity severity, String mitigation) {
    }

    private static final Rule SECURITY_RULE = new Rule(
            "Change to the authentication/authorization path could open a route unintentionally or lock "
                    + "legitimate callers out (SecurityConfig ends in denyAll, so every new route needs an explicit rule).",
            Severity.HIGH,
            "Add one explicit matcher per new route; extend the 401 (no key) / 403 (wrong identity) / 404 "
                    + "(cross-tenant) boundary tests in the integration suites before merging.");
    private static final Rule SCHEMA_RULE = new Rule(
            "Entity change is applied by Hibernate ddl-auto=update with no migration tooling, so a real "
                    + "persistent database would need a hand-written migration.",
            Severity.MEDIUM,
            "Keep schema changes additive (new nullable columns); record the migration need in README §10.");
    private static final Rule MONEY_RULE = new Rule(
            "Billing amounts could be miscalculated or an issued invoice could change retroactively.",
            Severity.HIGH,
            "Keep all money in integer cents; unit-test quota boundaries (exactly at, one over); never mutate "
                    + "an issued Invoice, only read through BillingService.getStatementForPeriod.");
    private static final Rule HOT_PATH_RULE = new Rule(
            "Extra work on the public redirect hot path adds latency to every redirect.",
            Severity.MEDIUM,
            "Keep synchronous redirect work to indexed lookups and the atomic click UPDATE; move anything "
                    + "else to the bounded async executor; evict CachedShortCodeLookup on change.");
    private static final Rule FAIRNESS_RULE = new Rule(
            "Rate-limit change could let one tenant consume another tenant's capacity.",
            Severity.MEDIUM,
            "Keep buckets keyed strictly by tenantId; extend TenantRateLimiterServiceTest's isolation case.");
    private static final Rule EXTERNAL_RULE = new Rule(
            "Dependency on an external safety service: an outage could block link creation or let unsafe links through.",
            Severity.MEDIUM,
            "Keep the feature flag and the fail-open policy explicit and logged; test both the reject and the timeout path.");
    private static final Rule COVERAGE_RULE = new Rule(
            "New code lowers line coverage below the 80% JaCoCo gate, failing mvn verify.",
            Severity.LOW,
            "Write tests per the TESTS artifact alongside each task, not after.");

    private final PromptSupport prompts;

    public RiskReviewerAgent(PromptSupport prompts) {
        this.prompts = prompts;
    }

    @Override
    public SdlcStage stage() {
        return SdlcStage.RISKS;
    }

    @Override
    public List<SdlcStage> dependsOn() {
        return List.of(SdlcStage.REQUIREMENTS, SdlcStage.IMPACT, SdlcStage.PLAN);
    }

    @Override
    public Class<RiskAssessment> outputType() {
        return RiskAssessment.class;
    }

    @Override
    public RiskAssessment runDeterministic(AgentContext context) {
        ImpactAnalysis impact = context.impactAnalysis();
        RequirementSpec spec = context.requirementSpec();

        Map<Rule, List<String>> triggered = new LinkedHashMap<>();
        for (ImpactedComponent ic : impact.impactedComponents()) {
            String name = ic.component();
            Layer layer = context.catalog().find(name).map(c -> c.layer()).orElse(null);
            if (layer == Layer.SECURITY) {
                add(triggered, SECURITY_RULE, name);
            }
            if (layer == Layer.DATA) {
                add(triggered, SCHEMA_RULE, name);
            }
            if (Set.of("BillingService", "InvoiceService", "UsageMeteringService", "InvoicePdfGenerator").contains(name)) {
                add(triggered, MONEY_RULE, name);
            }
            if (Set.of("RedirectController", "CachedShortCodeLookup").contains(name)) {
                add(triggered, HOT_PATH_RULE, name);
            }
            if (Set.of("TenantRateLimiterService", "RateLimitFilter").contains(name)) {
                add(triggered, FAIRNESS_RULE, name);
            }
            if ("FeignUrlSafetyChecker".equals(name)) {
                add(triggered, EXTERNAL_RULE, name);
            }
        }

        List<Risk> risks = new ArrayList<>();
        for (Map.Entry<Rule, List<String>> e : triggered.entrySet()) {
            Rule r = e.getKey();
            risks.add(new Risk("R-" + (risks.size() + 1), r.description(), r.severity(), r.mitigation(), e.getValue()));
        }
        List<Ambiguity> ambiguities = spec.ambiguities();
        if (!ambiguities.isEmpty()) {
            String ids = String.join(", ", ambiguities.stream().map(Ambiguity::id).toList());
            risks.add(new Risk("R-" + (risks.size() + 1),
                    ambiguities.size() + " assumption(s) may not match the requirement owner's intent (" + ids + ").",
                    Severity.MEDIUM,
                    "Confirm " + ids + " with the requirement owner before implementation starts; the TESTS "
                            + "artifact pins each assumption in a test so a changed answer has one obvious place to update.",
                    List.of()));
        }
        risks.add(new Risk("R-" + (risks.size() + 1), COVERAGE_RULE.description(), COVERAGE_RULE.severity(),
                COVERAGE_RULE.mitigation(), List.of()));
        return new RiskAssessment(risks);
    }

    private static void add(Map<Rule, List<String>> triggered, Rule rule, String component) {
        triggered.computeIfAbsent(rule, k -> new ArrayList<>()).add(component);
    }

    @Override
    public String systemPrompt() {
        return prompts.system("""
                Role: risk reviewer.
                Identify concrete delivery and production risks (R-n) of implementing this plan in this \
                codebase — security boundaries, data/schema changes without migrations, money correctness, \
                hot-path latency, tenant isolation and fairness, external dependencies, and open assumptions. \
                Give each a severity (LOW, MEDIUM, HIGH), a specific mitigation (every HIGH risk must have one), \
                and the catalog components it concerns.
                """);
    }

    @Override
    public String userPrompt(AgentContext context) {
        return prompts.requirementBlock(context)
                + prompts.catalogBlock(context.catalog())
                + prompts.artifactBlock("Requirement spec", context.requirementSpec())
                + prompts.artifactBlock("Impact analysis", context.impactAnalysis())
                + prompts.artifactBlock("Implementation plan", context.implementationPlan());
    }
}
