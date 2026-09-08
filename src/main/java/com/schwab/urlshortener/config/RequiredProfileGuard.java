package com.schwab.urlshortener.config;

import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;

/**
 * Refuses to start unless a real Spring profile was explicitly selected —
 * closes a genuine production blocker a review found: {@code
 * spring.profiles.active=dev} previously sat directly in the common
 * application.properties, meaning a plain {@code java -jar} with no
 * arguments silently activated the LEAST secure configuration (H2
 * console reachable, demo tenants seeded, their API keys printed to
 * logs, verbose security logging) rather than either a safe default or
 * a loud failure demanding a real choice.
 *
 * Implemented as an {@link ApplicationListener} on {@link
 * ApplicationEnvironmentPreparedEvent} — NOT a plain {@code @Component}
 * bean — because that event fires before the ApplicationContext (and
 * therefore component scanning) even exists; a normal bean would be
 * created far too late to prevent dev's properties from ever having been
 * applied. Registered directly against SpringApplication in main(), the
 * standard way to hook this specific, very early point in startup.
 */
public class RequiredProfileGuard implements ApplicationListener<ApplicationEnvironmentPreparedEvent>, Ordered {

    @Override
    public void onApplicationEvent(ApplicationEnvironmentPreparedEvent event) {
        ConfigurableEnvironment environment = event.getEnvironment();
        if (environment.getActiveProfiles().length == 0) {
            throw new IllegalStateException(
                    "No Spring profile selected. Refusing to start with an implicit default profile: this "
                            + "application's base configuration alone is not a safe posture to run with by "
                            + "accident (in-memory H2, no admin key configured, etc). Start with an explicit "
                            + "profile — for local development: --spring.profiles.active=dev "
                            + "(or java -jar app.jar --spring.profiles.active=dev); for production: "
                            + "--spring.profiles.active=prod, with every environment variable "
                            + "application-prod.properties documents as required actually set. "
                            + "See README.md \"Setup Instructions\" for the full list."
            );
        }
    }

    /** Run early relative to other environment-prepared listeners, though the check itself
     *  doesn't depend on ordering — it only reads what profiles were resolved from
     *  command-line args / environment variables, which happens before this event fires
     *  regardless of listener order. */
    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 10;
    }
}
