package com.schwab.urlshortener.workflow.agents;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.schwab.urlshortener.agentic.ComponentCatalog;
import com.schwab.urlshortener.agentic.model.ImpactAnalysis;
import com.schwab.urlshortener.agentic.model.ImplementationPlan;
import com.schwab.urlshortener.agentic.model.RequirementSpec;
import com.schwab.urlshortener.agentic.model.RiskAssessment;
import com.schwab.urlshortener.workflow.WorkflowProperties;
import com.schwab.urlshortener.workflow.artifact.PolicyReport;
import com.schwab.urlshortener.workflow.artifact.PolicyReport.Severity;
import com.schwab.urlshortener.workflow.artifact.PolicyReport.Violation;
import com.schwab.urlshortener.workflow.audit.SecretPatterns;
import com.schwab.urlshortener.workflow.workspace.ChangePatch;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic, configurable security and policy rules for the
 * SECURITY_POLICY_VALIDATION stage, specific to this codebase's documented
 * constraints:
 *
 * <ul>
 *   <li><b>POL-SECRET</b> (BLOCKER): no credential-shaped string in the requirement, any upstream artifact, or
 *       the patch diff. Matches are masked in the report.</li>
 *   <li><b>POL-SCOPE</b> (BLOCKER): the requirement asks for something out of product scope by policy
 *       ({@code app.workflow.policy.forbidden-terms}; default: payment processing, see README §10).</li>
 *   <li><b>POL-AUTHZ</b> (BLOCKER): a change to a SECURITY-layer component must carry a HIGH risk naming it,
 *       with a mitigation.</li>
 *   <li><b>POL-PROTECTED</b> (WARNING): the patch touches protected paths. The implementation task already
 *       required approval for it, and this records it for release review.</li>
 *   <li><b>POL-TENANT</b> (WARNING): tenant-scoped data access changes must keep queries scoped by tenantId.</li>
 *   <li><b>POL-PII</b> (WARNING): the requirement involves recording personal data.</li>
 * </ul>
 */
@Component
public class PolicyEngine {

    private static final Pattern PII = Pattern.compile(
            "(?i)\\b(log|store|record|persist|capture|track)\\w*\\b.{0,60}\\b(ip address|user agent|user-agent|email|full (destination )?url|referr?er)");

    private static final List<String> TENANT_DATA_COMPONENTS = List.of("UrlMappingRepository", "UrlShortenerServiceImpl", "AdminService");

    public static final List<String> RULES = List.of("POL-SECRET", "POL-SCOPE", "POL-AUTHZ", "POL-PROTECTED", "POL-TENANT", "POL-PII");

    private final ComponentCatalog catalog;
    private final ObjectMapper objectMapper;
    private final List<String> forbiddenTerms;

    public PolicyEngine(ComponentCatalog catalog, ObjectMapper objectMapper, WorkflowProperties properties) {
        this.catalog = catalog;
        this.objectMapper = objectMapper;
        this.forbiddenTerms = properties.policy().forbiddenTerms();
    }

    public PolicyReport evaluate(String requirement, RequirementSpec spec, ImpactAnalysis design, ImplementationPlan plan,
                                 RiskAssessment risks, ChangePatch patch) {
        List<Violation> violations = new ArrayList<>();

        Map<String, String> scanned = new LinkedHashMap<>();
        scanned.put("requirement", requirement);
        scanned.put("requirement-spec", json(spec));
        scanned.put("architecture-design", json(design));
        scanned.put("implementation-plan", json(plan));
        scanned.put("risk-assessment", json(risks));
        scanned.put("change-patch", patch.unifiedDiff() == null ? json(patch.files()) : patch.unifiedDiff());
        scanned.forEach((location, text) -> SecretPatterns.PATTERNS.forEach((kind, pattern) -> {
            Matcher m = pattern.matcher(text);
            if (m.find()) {
                violations.add(new Violation("POL-SECRET", Severity.BLOCKER,
                        "Credential-shaped value (" + kind + ") found: " + mask(m.group()) + ". Remove it and rotate the credential.",
                        location));
            }
        }));

        String lower = requirement.toLowerCase(Locale.ROOT);
        for (String term : forbiddenTerms) {
            if (lower.contains(term.toLowerCase(Locale.ROOT))) {
                violations.add(new Violation("POL-SCOPE", Severity.BLOCKER,
                        "Requirement asks for '" + term + "', which is out of product scope by policy (billing is metering and rating only; "
                                + "no payment processing).", "requirement"));
            }
        }

        List<String> impacted = Checks.safe(design.impactedComponents()).stream().map(ImpactAnalysis.ImpactedComponent::component).toList();
        for (String component : impacted) {
            boolean security = catalog.find(component).map(c -> c.layer() == ComponentCatalog.Layer.SECURITY).orElse(false);
            if (security) {
                boolean acknowledged = Checks.safe(risks.risks()).stream().anyMatch(r -> r.severity() == RiskAssessment.Severity.HIGH
                        && r.mitigation() != null && !r.mitigation().isBlank() && Checks.safe(r.components()).contains(component));
                if (!acknowledged) {
                    violations.add(new Violation("POL-AUTHZ", Severity.BLOCKER, "Security-layer component '" + component
                            + "' changes without a HIGH risk naming it and a mitigation.", "risk-assessment"));
                }
            }
            if (TENANT_DATA_COMPONENTS.contains(component)) {
                violations.add(new Violation("POL-TENANT", Severity.WARNING, "'" + component + "' touches tenant-scoped data: every new "
                        + "query must be scoped by (…, tenantId), and cross-tenant access must return 404, not 403.", "architecture-design"));
            }
        }

        if (!patch.protectedPathsTouched().isEmpty()) {
            violations.add(new Violation("POL-PROTECTED", Severity.WARNING, "Patch touches protected paths " + patch.protectedPathsTouched()
                    + " (approved at the implementation checkpoint).", "change-patch"));
        }

        Matcher pii = PII.matcher(requirement);
        if (pii.find()) {
            violations.add(new Violation("POL-PII", Severity.WARNING, "Requirement involves recording personal data ('" + pii.group(2)
                    + "'). Define retention and redaction first (README §10 flags verbatim URL logging).", "requirement"));
        }

        int blockers = (int) violations.stream().filter(v -> v.severity() == Severity.BLOCKER).count();
        return new PolicyReport(blockers == 0, blockers, violations.size() - blockers, RULES, List.copyOf(violations));
    }

    static String mask(String secret) {
        return secret.length() <= 8 ? "****" : secret.substring(0, 6) + "…(" + secret.length() + " chars)";
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unable to serialize artifact for policy scan", e);
        }
    }
}
