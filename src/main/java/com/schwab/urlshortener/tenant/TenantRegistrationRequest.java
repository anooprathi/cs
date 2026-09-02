package com.schwab.urlshortener.tenant;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record TenantRegistrationRequest(

        @NotBlank(message = "name must not be blank")
        @Size(max = 100, message = "name must not exceed 100 characters")
        String name,

        /** Optional; defaults to STANDARD. In a real system, plan upgrades would go
         *  through billing, not free self-selection — noted as a prototype shortcut. */
        RateLimitPlan plan
) {
}
