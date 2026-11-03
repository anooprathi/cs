package com.schwab.urlshortener.util;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Credential-shaped patterns, shared by:
 * <ul>
 *   <li>request intake: a requirement containing one is rejected before it is stored;</li>
 *   <li>the LLM boundary: an outgoing prompt containing one is blocked before it is sent;</li>
 *   <li>the policy engine (POL-SECRET detection in patches and artifacts);</li>
 *   <li>the audit log (redaction before anything is persisted or logged).</li>
 * </ul>
 * Pattern-based redaction is best-effort: it catches the formats listed here,
 * not every possible secret. See docs/governance.md.
 */
public final class SecretPatterns {

    public static final Map<String, Pattern> PATTERNS;

    static {
        Map<String, Pattern> p = new LinkedHashMap<>();
        p.put("tenant API key", Pattern.compile("usk_[A-Za-z0-9_-]{20,}"));
        p.put("Anthropic API key", Pattern.compile("sk-ant-[A-Za-z0-9_-]{16,}"));
        p.put("AWS access key", Pattern.compile("\\bAKIA[0-9A-Z]{16}\\b"));
        p.put("private key block", Pattern.compile("-----BEGIN [A-Z ]*PRIVATE KEY-----"));
        p.put("inline password", Pattern.compile("(?i)\\bpassword\\s*[:=]\\s*\\S{4,}"));
        p.put("bearer token", Pattern.compile("(?i)\\bbearer\\s+[A-Za-z0-9._~+/-]{20,}=*"));
        PATTERNS = Map.copyOf(p);
    }

    private SecretPatterns() {
    }

    /** Replaces every match with {@code [REDACTED:<kind>]}. Null-safe. */
    public static String redact(String text) {
        if (text == null) {
            return null;
        }
        String out = text;
        for (Map.Entry<String, Pattern> e : PATTERNS.entrySet()) {
            Matcher m = e.getValue().matcher(out);
            if (m.find()) {
                out = m.replaceAll(Matcher.quoteReplacement("[REDACTED:" + e.getKey() + "]"));
            }
        }
        return out;
    }

    /** The kinds of credential found in the given texts: names only, never the matched values. Sorted, null-safe. */
    public static java.util.List<String> kindsFound(String... texts) {
        java.util.TreeSet<String> kinds = new java.util.TreeSet<>();
        for (String text : texts) {
            if (text != null) {
                PATTERNS.forEach((kind, pattern) -> {
                    if (pattern.matcher(text).find()) {
                        kinds.add(kind);
                    }
                });
            }
        }
        return java.util.List.copyOf(kinds);
    }

    public static boolean containsSecret(String text) {
        return text != null && PATTERNS.values().stream().anyMatch(p -> p.matcher(text).find());
    }
}
