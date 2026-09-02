package com.schwab.urlshortener.util;

/**
 * The single source of truth for what a short code/custom alias is
 * allowed to look like. Previously this literal was duplicated three
 * times (RedirectController's path pattern, SecurityConfig's matching
 * requestMatcher, and ShortenUrlRequest's @Pattern) — a drifting edit to
 * one would silently desync routing from validation. All three now
 * reference this class.
 *
 * Both fields must stay compile-time constant expressions (plain string
 * literals / literal concatenations) since CHARSET_AND_LENGTH is used
 * inside a Spring MVC path-pattern regex segment via @GetMapping, and
 * ANCHORED for a Bean Validation @Pattern annotation — both are
 * annotation attributes, which the JLS requires to be constant
 * expressions.
 */
public final class ShortCodeFormat {

    private ShortCodeFormat() {
    }

    /** Unanchored — for embedding inside a larger pattern (a Spring path-variable regex). */
    public static final String CHARSET_AND_LENGTH = "[A-Za-z0-9_-]{4,20}";

    /** Anchored — for validating a whole standalone string (a request field). */
    public static final String ANCHORED = "^" + CHARSET_AND_LENGTH + "$";
}
