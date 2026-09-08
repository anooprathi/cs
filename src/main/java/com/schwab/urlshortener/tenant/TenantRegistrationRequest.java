package com.schwab.urlshortener.tenant;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Public self-registration — no plan field. This used to accept a
 * caller-selected RateLimitPlan (defaulting to STANDARD if omitted),
 * documented at the time as an accepted "prototype shortcut." A
 * production-readiness review correctly rejected that framing: an
 * unauthenticated caller choosing PREMIUM for free isn't a shortcut, it's
 * an open path to unlimited free premium accounts. Every tenant now
 * starts on STANDARD (enforced in TenantService.register, not just
 * defaulted here) with no way to request otherwise through this
 * endpoint — a plan upgrade is an authenticated administrative action
 * (see AdminController.updatePlan), never something the registrant
 * declares for themselves.
 */
public record TenantRegistrationRequest(
        @NotBlank(message = "name must not be blank")
        @Size(max = 100, message = "name must not exceed 100 characters")
        String name
) {
}
