package com.schwab.urlshortener.config;

import com.schwab.urlshortener.tenant.RateLimitPlan;
import com.schwab.urlshortener.tenant.TenantRegistrationRequest;
import com.schwab.urlshortener.tenant.TenantRegistrationResponse;
import com.schwab.urlshortener.tenant.TenantService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Dev-convenience only: seeds two demo tenants (one on each plan) so you
 * can start curl-ing the API immediately without first calling
 * POST /api/v1/tenants yourself. Guarded by BOTH the "dev" profile and
 * app.dev-seed.enabled so there is no risk of this ever running — and
 * printing generated secrets to logs — in a production environment.
 */
@Component
@Profile("dev")
@ConditionalOnProperty(name = "app.dev-seed.enabled", havingValue = "true")
@Slf4j
public class DevDataSeeder implements CommandLineRunner {

    private final TenantService tenantService;

    public DevDataSeeder(TenantService tenantService) {
        this.tenantService = tenantService;
    }

    @Override
    public void run(String... args) {
        TenantRegistrationResponse standard = tenantService.register(
                new TenantRegistrationRequest("demo-standard-tenant"));
        TenantRegistrationResponse premiumSeed = tenantService.register(
                new TenantRegistrationRequest("demo-premium-tenant"));
        // Registration itself no longer accepts a plan — even the demo
        // seed data goes through the real, correct flow: register on
        // STANDARD, then upgrade via the same admin action a real operator
        // would use (see TenantRegistrationRequest's Javadoc for why).
        tenantService.updatePlan(premiumSeed.tenantId(), RateLimitPlan.PREMIUM);

        log.info("=================================================================");
        log.info(" DEV SEED DATA — demo tenants (see README.md for curl examples)");
        log.info(" STANDARD tenant '{}' -> API key: {}", standard.name(), standard.apiKey());
        log.info(" PREMIUM  tenant '{}' -> API key: {} (registered STANDARD, upgraded via admin action)", premiumSeed.name(), premiumSeed.apiKey());
        log.info("=================================================================");
    }
}
