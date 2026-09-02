package com.schwab.urlshortener.admin;

import com.schwab.urlshortener.tenant.RateLimitPlan;
import jakarta.validation.constraints.NotNull;

public record AdminUpdatePlanRequest(
        @NotNull(message = "plan must not be null")
        RateLimitPlan plan
) {
}
