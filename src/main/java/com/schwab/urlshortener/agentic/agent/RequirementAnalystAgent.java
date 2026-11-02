package com.schwab.urlshortener.agentic.agent;

import com.schwab.urlshortener.agentic.model.RequirementSpec;
import com.schwab.urlshortener.agentic.model.RequirementSpec.AcceptanceCriterion;
import com.schwab.urlshortener.agentic.model.RequirementSpec.Ambiguity;
import com.schwab.urlshortener.agentic.model.RequirementSpec.Statement;
import com.schwab.urlshortener.agentic.model.SdlcStage;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * REQUIREMENTS stage: free text → numbered requirements, acceptance
 * criteria, ambiguities with explicit assumptions, and out-of-scope items.
 *
 * Deterministic rules, in order:
 *  - Statements containing an out-of-scope marker ("out of scope", "not
 *    required", "do not", ...) go to outOfScope, not to requirements.
 *  - Statements matching an NFR keyword (latency, secure, scalable, ...)
 *    become NFR-n; everything else becomes FR-n.
 *  - Each FR and NFR gets exactly one acceptance criterion, templated from
 *    the statement itself — so coverage is guaranteed by construction.
 *  - A fixed lexicon of vague terms ("fast", "scalable", "etc", ...) maps
 *    each to a clarifying question and a concrete default assumption
 *    grounded in this codebase's existing conventions; two structural gap
 *    checks (no actor named, no failure behavior named) do the same.
 */
@Component
public class RequirementAnalystAgent implements SdlcAgent<RequirementSpec> {

    private static final Pattern OUT_OF_SCOPE = Pattern.compile(
            "(?i)\\b(out of scope|not required|not needed|no need to|do not|don't|won't|will not|exclud)");

    private static final List<String> NFR_KEYWORDS = List.of(
            "latency", "performan", "fast", "quick", "throughput", "scal", "availab", "uptime", "reliab",
            "robust", "secur", "audit", "complian", "privacy", "encrypt", "observab", "monitor", "logging",
            "p95", "p99", "millisecond", "concurren", "resilien");

    private static final Pattern ACTOR = Pattern.compile("(?i)\\b(tenant|admin|operator|user|caller|client|customer|owner)");
    private static final Pattern FAILURE_BEHAVIOR = Pattern.compile(
            "(?i)\\b(error|fail|invalid|reject|denied|4\\d\\d|5\\d\\d|not found|conflict)");
    private static final Pattern ADMIN_ACTOR = Pattern.compile("(?i)\\b(admin|operator)");

    /** term → {question, assumption}. Assumptions are deliberately grounded in this repo's existing conventions. */
    private static final Map<String, String[]> VAGUE_TERMS = new LinkedHashMap<>();

    static {
        VAGUE_TERMS.put("fast", new String[]{"What latency target is required, at which percentile and load?",
                "p95 latency under 200 ms at current integration-test load; no new load-test infrastructure is in scope."});
        VAGUE_TERMS.put("quickly", VAGUE_TERMS.get("fast"));
        VAGUE_TERMS.put("scalable", new String[]{"What scale must be supported (requests/second, data volume, instances)?",
                "No new per-instance state beyond what README §7 already documents; multi-instance correctness via existing Redis-backed patterns."});
        VAGUE_TERMS.put("securely", new String[]{"Which threat or control is meant by 'secure'?",
                "Reuse the existing identity model (X-API-Key tenant, X-Admin-Key operator); no new identity system."});
        VAGUE_TERMS.put("user-friendly", new String[]{"Which user-facing outcome defines 'user-friendly'?",
                "API-only change; errors use the shared GlobalExceptionHandler envelope with actionable messages."});
        VAGUE_TERMS.put("easy", VAGUE_TERMS.get("user-friendly"));
        VAGUE_TERMS.put("etc", new String[]{"Is the list exhaustive, and if not, what else is included?",
                "Only the explicitly named items are in scope."});
        VAGUE_TERMS.put("and so on", VAGUE_TERMS.get("etc"));
        VAGUE_TERMS.put("appropriate", new String[]{"Who decides what is appropriate, and by what rule?",
                "Behavior is driven by a type-safe @ConfigurationProperties value with a documented, conservative default."});
        VAGUE_TERMS.put("as needed", VAGUE_TERMS.get("appropriate"));
        VAGUE_TERMS.put("reasonable", VAGUE_TERMS.get("appropriate"));
        VAGUE_TERMS.put("some", new String[]{"What exact quantity or limit is intended?",
                "A configurable limit with a conservative default."});
        VAGUE_TERMS.put("several", VAGUE_TERMS.get("some"));
        VAGUE_TERMS.put("many", VAGUE_TERMS.get("some"));
        VAGUE_TERMS.put("real-time", new String[]{"What is the maximum acceptable delay?",
                "Visible on the next request; no asynchronous lag beyond the existing async usage metering."});
        VAGUE_TERMS.put("immediately", VAGUE_TERMS.get("real-time"));
        VAGUE_TERMS.put("robust", new String[]{"Which failure modes must be handled?",
                "Failures surface as typed 4xx/5xx through GlobalExceptionHandler; nothing fails silently."});
        VAGUE_TERMS.put("safer", new String[]{"Which threats should 'safer' address: malicious destinations, link enumeration, abuse, or account takeover?",
                "Reject known-malicious destinations through the existing URL-safety check (FeignUrlSafetyChecker); no new identity model."});
        VAGUE_TERMS.put("safe", VAGUE_TERMS.get("safer"));
        VAGUE_TERMS.put("useful", new String[]{"Which decisions should the analytics support, and which metrics answer them?",
                "Per-link click counts over time only, with no new personal data collected."});
        VAGUE_TERMS.put("analytics", new String[]{"Which metrics, at what granularity and retention?",
                "Aggregate counters only, consistent with the existing analytics scope (ENGINEERING_SUMMARY.md §3)."});
    }

    private final PromptSupport prompts;

    public RequirementAnalystAgent(PromptSupport prompts) {
        this.prompts = prompts;
    }

    @Override
    public SdlcStage stage() {
        return SdlcStage.REQUIREMENTS;
    }

    @Override
    public List<SdlcStage> dependsOn() {
        return List.of();
    }

    @Override
    public Class<RequirementSpec> outputType() {
        return RequirementSpec.class;
    }

    @Override
    public RequirementSpec runDeterministic(AgentContext context) {
        String requirement = context.requirement();
        List<String> statements = TextSupport.statements(requirement);

        List<Statement> functional = new ArrayList<>();
        List<Statement> nonFunctional = new ArrayList<>();
        List<String> outOfScope = new ArrayList<>();
        for (String s : statements) {
            String normalized = TextSupport.normalize(s);
            if (OUT_OF_SCOPE.matcher(s).find()) {
                outOfScope.add(s);
            } else if (NFR_KEYWORDS.stream().anyMatch(k -> TextSupport.matchesKeyword(normalized, k))) {
                nonFunctional.add(new Statement("NFR-" + (nonFunctional.size() + 1), s));
            } else {
                functional.add(new Statement("FR-" + (functional.size() + 1), s));
            }
        }
        outOfScope.add("Applying code changes automatically: every artifact in this run is a proposal for human review.");

        List<AcceptanceCriterion> criteria = new ArrayList<>();
        for (Statement fr : functional) {
            // Actor decided per statement, not per requirement: "Admins should see X" in one line
            // must not turn every other line's criterion into an operator scenario.
            String given = ADMIN_ACTOR.matcher(fr.text()).find()
                    ? "an operator authenticated with X-Admin-Key"
                    : "an authenticated tenant (X-API-Key) using the management API";
            criteria.add(new AcceptanceCriterion("AC-" + (criteria.size() + 1), given,
                    "the behavior described in " + fr.id() + " is exercised",
                    "the system observably satisfies: \"" + TextSupport.stripTrailingPunctuation(fr.text()) + "\"",
                    fr.id()));
        }
        for (Statement nfr : nonFunctional) {
            criteria.add(new AcceptanceCriterion("AC-" + (criteria.size() + 1),
                    "the agreed measurement conditions for " + nfr.id(),
                    "the quality attribute is measured or verified",
                    "the result meets: \"" + TextSupport.stripTrailingPunctuation(nfr.text()) + "\"",
                    nfr.id()));
        }

        List<Ambiguity> ambiguities = detectAmbiguities(requirement, statements);

        String summary = statements.isEmpty() ? requirement.strip() : statements.get(0);
        if (summary.length() > 300) {
            summary = summary.substring(0, 297) + "...";
        }
        return new RequirementSpec(context.title(), summary, functional, nonFunctional, criteria, ambiguities, outOfScope);
    }

    private List<Ambiguity> detectAmbiguities(String requirement, List<String> statements) {
        List<Ambiguity> out = new ArrayList<>();
        Set<String> seenAssumptions = new LinkedHashSet<>();
        for (Map.Entry<String, String[]> term : VAGUE_TERMS.entrySet()) {
            for (String s : statements) {
                if (TextSupport.matchesKeyword(TextSupport.normalize(s), term.getKey())
                        && seenAssumptions.add(term.getValue()[1])) {
                    out.add(new Ambiguity("AMB-" + (out.size() + 1),
                            "\"" + term.getKey() + "\" in: " + s, term.getValue()[0], term.getValue()[1]));
                    break;
                }
            }
        }
        if (!ACTOR.matcher(requirement).find()) {
            out.add(new Ambiguity("AMB-" + (out.size() + 1), "No actor is named in the requirement.",
                    "Who is allowed to use this capability?",
                    "An authenticated tenant via X-API-Key, tenant-scoped like the rest of the management API "
                            + "(cross-tenant access returns 404)."));
        }
        if (!FAILURE_BEHAVIOR.matcher(requirement).find()) {
            out.add(new Ambiguity("AMB-" + (out.size() + 1), "No failure or invalid-input behavior is specified.",
                    "What should happen on invalid input or when the operation cannot be completed?",
                    "Typed errors through GlobalExceptionHandler: 400 for invalid input, 404 for unknown or "
                            + "not-owned resources, 409 for conflicts."));
        }
        return out;
    }

    @Override
    public String systemPrompt() {
        return prompts.system("""
                Role: requirements analyst.
                Normalize the requirement into functional (FR-n) and non-functional (NFR-n) statements, \
                testable acceptance criteria (AC-n, given/when/then, each pointing at the FR/NFR it verifies; \
                every FR and NFR needs at least one), ambiguities (AMB-n) each with a concrete assumption \
                grounded in the codebase's existing conventions, and explicit out-of-scope items.
                Do not add requirements the text does not support; record a gap as an ambiguity instead.
                """);
    }

    @Override
    public String userPrompt(AgentContext context) {
        return prompts.requirementBlock(context);
    }
}
