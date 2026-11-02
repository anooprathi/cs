package com.schwab.urlshortener.workflow.engine;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

/** SHA-256 helpers for content hashes and input fingerprints. */
public final class Hashes {

    private Hashes() {
    }

    public static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /**
     * The input fingerprint of one task execution. Two executions with equal
     * fingerprints were given identical inputs, so a deterministic-by-contract
     * executor would produce the same result, and the earlier output can be reused.
     *
     * Covers: executor key and version (a logic change invalidates reuse), the
     * execution mode, the requirement text hash, and the content hash of every
     * declared input, keyed by name and sorted so declaration order is irrelevant.
     */
    public static String fingerprint(String executorKey, String executorVersion, String mode, String requirementHash,
                                     Map<String, String> inputHashes) {
        StringBuilder sb = new StringBuilder()
                .append("executor=").append(executorKey).append('@').append(executorVersion).append('\n')
                .append("mode=").append(mode).append('\n')
                .append("requirement=").append(requirementHash).append('\n');
        new TreeMap<>(inputHashes).forEach((name, hash) -> sb.append("input:").append(name).append('=').append(hash).append('\n'));
        return sha256(sb.toString());
    }

    public static String shortHash(String hash) {
        return hash == null ? "-" : hash.substring(0, Math.min(12, hash.length()));
    }
}
