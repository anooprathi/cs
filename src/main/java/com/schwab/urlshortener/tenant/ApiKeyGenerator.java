package com.schwab.urlshortener.tenant;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Generates opaque, high-entropy API keys and hashes them for storage/lookup.
 * See {@link Tenant} for the rationale on using SHA-256 rather than BCrypt here.
 */
public final class ApiKeyGenerator {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final String KEY_PREFIX = "usk_"; // "url-shortener key" — mirrors real-world API key prefixes (sk_, pk_, ...)

    private ApiKeyGenerator() {
    }

    /** Generates a new random API key, e.g. {@code usk_3f7a9c1e...}. Never persisted in this form. */
    public static String generate() {
        byte[] randomBytes = new byte[32];
        SECURE_RANDOM.nextBytes(randomBytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
        return KEY_PREFIX + token;
    }

    /** SHA-256 hash of the raw key, hex-encoded, for indexed lookup / storage. */
    public static String hash(String rawApiKey) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(rawApiKey.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashBytes);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is a JVM-guaranteed algorithm; this branch is unreachable in practice.
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
