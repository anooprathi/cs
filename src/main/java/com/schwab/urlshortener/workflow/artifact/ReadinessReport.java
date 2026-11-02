package com.schwab.urlshortener.workflow.artifact;

import java.util.List;

/**
 * RELEASE_READINESS output: an explicit checklist computed from the upstream
 * reports. {@code ready} means every BLOCKING check passed. Advisory checks are
 * reported but don't block.
 *
 * {@code approvalSubject} lists the exact artifact versions (name@vN#hash) this
 * report vouches for, notably the change-patch. Approving this report version
 * therefore approves exactly that patch version. If the patch changes, release
 * readiness re-runs, the report gets a new version, and the old approval goes
 * stale. Promotion checks this binding.
 */
public record ReadinessReport(boolean ready, List<Check> checks, List<String> openAssumptions, List<String> approvalSubject,
                              String recommendation) {

    public record Check(String id, String description, boolean blocking, boolean passed, String detail) {
    }
}
