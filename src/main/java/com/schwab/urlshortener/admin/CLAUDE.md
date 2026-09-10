# CLAUDE.md

Guidance for Claude Code when working in `src/main/java/com/schwab/urlshortener/admin/`.

`AdminService` is an orchestration layer, not an aggregate owner — it composes across three aggregates
that each already have their own service/repository (`TenantService`, `UrlMappingRepository`,
`UsageMeteringService`/`TenantUsageRecordRepository`) rather than owning persistence of its own. It plays
the same architectural role for cross-tenant admin views that `UrlShortenerServiceImpl` plays for the URL
aggregate. Don't fold admin-specific logic into `TenantService` just because most calls delegate there —
the separation is deliberate so "what can cross-tenant admin code see/touch" stays answerable by reading
one class.

Every mutating admin method delegates the actual write to the aggregate's own service (`TenantService`)
and then maps the result to an admin-shaped DTO — `AdminService` itself never calls a repository's `save`.
The one exception is the two read endpoints that compose data from multiple repositories directly
(`getTenantDetail`, `getUsageSummary`), since there's no natural "owning service" for that composed shape.

`getUsageSummary` resolves tenant display names via `tenantService.findByIds(...)` scoped to only the
tenant ids that actually appear in this period's usage records — not `tenantService.listAll()`. That was a
real bug fix (see `AdminServiceTest`): loading every registered tenant to build a name-lookup map scales
with total tenant count instead of with the period's actual activity, and `activeTenantCount` must mean
"tenants with usage this period," matching `byTenant`'s scope — not "every tenant ever registered." A
tenant deleted since usage was recorded still appears in the breakdown as `"(deleted tenant {id})"` rather
than being silently dropped or throwing.

`listTenantUrls` builds its own default `Sort.by(DESC, "createdAt")` only when the caller didn't supply
one (`pageable.getSort().isSorted()`) — it used to unconditionally override any caller-supplied `?sort=`,
making it inoperable. If you touch this method, keep that conditional; see
`listTenantUrls_explicitSortRequested_isHonoredNotOverridden` for the regression it guards.

`listTenantUrls` and `getTenantDetail` call `tenantService.getTenantById` before touching links/usage so an
unknown tenant id 404s cleanly rather than returning an empty page indistinguishable from "zero links."

`RateLimitBurstTest.java` in this directory is a stray manual test script (a `main()` method hitting
`localhost:8080` with a hardcoded API key) — it is not part of the admin feature set, isn't wired into the
Spring context, and doesn't belong under `main/java` (it's untracked in git). Don't treat it as documented
admin functionality or extend it; if cleaning up the package, it's a candidate for deletion or relocation
to a scratch/manual-testing location outside `src/main`.
