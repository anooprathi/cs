package com.schwab.urlshortener.workflow.engine;

/**
 * A versioned, content-addressed pointer to one stored artifact, e.g.
 * {@code requirement-spec@v2 (sha256:3f1a…)}. Tasks receive their inputs only as
 * refs, so a downstream task always knows exactly which version it consumed.
 */
public record ArtifactRef(Long artifactId, String name, int version, String contentHash, String producedByTask) {

    public String label() {
        return name + "@v" + version;
    }
}
