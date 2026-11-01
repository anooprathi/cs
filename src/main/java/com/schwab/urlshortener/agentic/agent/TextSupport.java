package com.schwab.urlshortener.agentic.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Small, deterministic text helpers used by the DETERMINISTIC_DEMO agents.
 * Intentionally simple (lowercasing, sentence splitting, word-start keyword
 * matching) — this is a transparent rule-based stand-in, and anything
 * cleverer would make its output harder to predict and explain.
 */
final class TextSupport {

    private static final Pattern BULLET = Pattern.compile("^\\s*(?:[-*•]|\\d+[.)])\\s*");
    private static final Pattern SENTENCE_END = Pattern.compile("(?<=[.!?])\\s+");

    private TextSupport() {
    }

    /** Splits a requirement into statements: one per line/bullet, then one per sentence. Drops fragments under 3 words. */
    static List<String> statements(String requirement) {
        List<String> out = new ArrayList<>();
        for (String line : requirement.split("\\R")) {
            String stripped = BULLET.matcher(line).replaceFirst("").trim();
            if (stripped.isEmpty()) {
                continue;
            }
            for (String sentence : SENTENCE_END.split(stripped)) {
                String s = sentence.trim();
                if (s.split("\\s+").length >= 3) {
                    out.add(s);
                }
            }
        }
        return out;
    }

    static String normalize(String text) {
        return text.toLowerCase(Locale.ROOT);
    }

    /**
     * True if {@code keyword} occurs in {@code normalizedText} starting at a
     * word boundary — so "expir" matches "expired"/"expiry", and "api"
     * matches "api" but not "rapid".
     */
    static boolean matchesKeyword(String normalizedText, String keyword) {
        return Pattern.compile("\\b" + Pattern.quote(keyword.toLowerCase(Locale.ROOT))).matcher(normalizedText).find();
    }

    static List<String> matchedKeywords(String normalizedText, List<String> keywords) {
        return keywords.stream().filter(k -> matchesKeyword(normalizedText, k)).toList();
    }

    static String stripTrailingPunctuation(String s) {
        return s.replaceAll("[.!?;:]+$", "");
    }
}
