package com.schwab.urlshortener.agentic.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * A human review decision. {@code reviewer} is a self-declared name. The admin
 * credential is one shared key, so the system cannot verify who is reviewing.
 * That limitation is the same one README §10 already names for admin actions.
 * A comment is required when requesting changes (enforced in SdlcRunService).
 */
public record SdlcReviewRequest(
        @NotNull Decision decision,
        @NotBlank @Size(max = 100) String reviewer,
        @Size(max = 2000) String comment
) {

    public enum Decision {
        APPROVED,
        CHANGES_REQUESTED
    }
}
