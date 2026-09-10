# CLAUDE.md

Guidance for Claude Code when working in `src/main/java/com/schwab/urlshortener/config/`.

- **`AsyncConfig`**: names the metering executor bean `meteringTaskExecutor` — `ThreadPoolTaskExecutor` with
  `corePoolSize=4`, `maxPoolSize=16`, `queueCapacity=500`, `CallerRunsPolicy` (never `AbortPolicy`, so a full
  queue runs metering synchronously on the caller's thread instead of dropping the usage record — the one
  case where losing throughput is preferable to losing data). Also installs a logging
  `AsyncUncaughtExceptionHandler`, since exceptions thrown inside `@Async void` methods can't propagate to
  any caller and would otherwise vanish silently. `app.async.metering.enabled=false` (set in
  `application-test.properties`) swaps this pool for a same-thread `SyncTaskExecutor` — needed so integration
  tests that assert on a usage count right after creating a link/redirect aren't racing the async write; this
  is a test-determinism switch, not a workaround for a bug.
- **`CacheConfig`**: two Caffeine caches, each with its own tuning because their staleness tolerances differ —
  `shortCodeRedirects` (50,000 entries, 2 min TTL — a deactivated/expired link should stop resolving from
  cache reasonably fast) and `tenantPlans` (10,000 entries, 10 min TTL — plan changes are rare, deliberate
  admin actions that can tolerate more staleness). Registered as two explicit `Caffeine` builders on one
  `CaffeineCacheManager`, not one manager-wide spec, specifically so these two numbers can diverge.
- **`DevDataSeeder`**: gated by *both* `@Profile("dev")` and `app.dev-seed.enabled=true` (belt-and-suspenders —
  two independent conditions must hold before generated tenant API keys get logged to console). Registers via
  `TenantService.register` (always `STANDARD`) then explicitly upgrades one seed tenant to `PREMIUM` via
  `updatePlan` — deliberately exercising the same admin-only upgrade path a real operator would use, not a
  shortcut that bypasses it.
- **`RequiredProfileGuard`**: NOT a `@Component` — it's an `ApplicationListener<ApplicationEnvironmentPreparedEvent>`
  registered directly on `SpringApplication` in `main()`, because it has to reject a missing profile before the
  `ApplicationContext` (and therefore `dev`'s properties) is ever created. A normal bean would run too late to
  prevent that. If you ever need another very-early startup check, follow this same pattern rather than adding
  a `@Component` and expecting it to run before profile-specific properties apply.
- **`OpenApiConfig`**: registers two separate Swagger security schemes, `ApiKeyAuth` (`X-API-Key`, applied
  globally) and `AdminKeyAuth` (`X-Admin-Key`, applied only via `@SecurityRequirement("AdminKeyAuth")` on
  `AdminController`). If you add a new admin-only controller, it needs that same class-level annotation or its
  "Try it out" in Swagger UI will silently send the wrong header.
