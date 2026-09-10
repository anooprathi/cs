# CLAUDE.md

Guidance for Claude Code when working in `src/main/java/com/schwab/urlshortener/ratelimit/`.

## Policy/mechanism split

`TenantRateLimiterService` owns **policy**: which plan gets which limit, and bucket-key construction —
`"api:" + tenantId` and `"redirect:" + tenantId`. It delegates the actual counter/bucket **mechanism** to an
injected `RateLimiterBackend`, selected by mutually-exclusive `@ConditionalOnProperty` conditions on
`app.rate-limit.backend` (`local`, default via `matchIfMissing=true`, vs `redis`) — the same
one-interface-two-conditional-beans pattern as `UrlSafetyChecker` (see `service/CLAUDE.md`). If a requested
`RateLimitPlan` has no entry in `RateLimitProperties.plans()`, `TenantRateLimiterService.planLimits` throws
`IllegalStateException` — a missing plan config is a loud startup-adjacent failure, not a silent fallback to
some default limit.

## `LocalRateLimiterBackend` — cache key includes the limit itself

Bucket4j bakes its `Bandwidth` (the configured limit) into a `Bucket` at construction and has no API to
change it later. The Caffeine cache key is therefore `bucketKey + ":" + permitsPerMinute`, **not** just
`bucketKey` — if it were just the tenant/type key, a tenant upgraded `STANDARD`→`PREMIUM` mid-session would
keep resolving to the same cached `Bucket` built with the old (lower) limit forever, since the cache key
never changed even though the desired limit did. Qualifying the key with the limit makes a plan change look
like a fresh cache entry; the stale one just ages out via `expireAfterAccess(1h)` rather than leaking.
Bucket capacity is `100_000` entries. Refill is `Refill.greedy(permitsPerMinute, 1 minute)` — tokens trickle
back continuously rather than all arriving at once at a window boundary.

## `RedisRateLimiterBackend` — fixed window, not token bucket, and why

Deliberately a simpler algorithm than replicating `LocalRateLimiterBackend`'s token bucket over Redis (which
would need an atomic Lua script to stay race-free under concurrency). Mechanism: `INCR` a
per-tenant-per-minute key; on the first hit in a window (`count == 1`) set a 1-minute `EXPIRE`. Known,
accepted trade-off: a client can get up to ~2x the nominal limit in a burst straddling a window boundary
(a few requests right before reset, a fresh full window right after) — acceptable for "don't let one tenant
starve others," not acceptable if this were ever reused for a hard-capped exact quota.

**The race this backend explicitly guards against:** `INCR` and `EXPIRE` are two separate Redis calls, not
one atomic operation. If a request's `INCR` lands but the process crashes/times out/loses its connection
before the `EXPIRE` call, that key is left with **no TTL** — meaning it would never reset and would
permanently rate-limit whoever owns it, since the counter can never again equal exactly 1. The fix is the
self-healing check on every *non-first* hit (`count > 1`): read `getExpire()`, and if it's `null` or
negative (Redis's `-1` return means "key exists, no TTL set"; `-2` would mean the key doesn't exist, which
shouldn't happen right after an `INCR`), re-issue the `EXPIRE`. One extra Redis read per non-first hit in a
window; self-corrects within one request of the gap occurring rather than needing manual intervention.

Fails **open**, not closed — on `DataAccessException` (Redis unreachable) or a `null` `INCR` result, the
request is allowed. A rate limiter that's a hard dependency on every request would turn a Redis outage into
a full API outage, judged worse than temporarily not enforcing fair-share limits.

## `RateLimitFilter` — where it sits and what it does on rejection

Runs after `ApiKeyAuthenticationFilter` in the chain (root `CLAUDE.md` has the full pipeline diagram). It
reads `SecurityContextHolder`'s `Authentication` directly: if there's no `TenantPrincipal` principal yet, it
passes through untouched — meaning **unauthenticated routes (redirect, tenant registration, actuator,
Swagger) never go through this filter at all**. The public redirect's own fair-share check happens later,
inside `UrlShortenerServiceImpl.resolveAndRecordHit` (see `service/CLAUDE.md`), scoped to the link's owning
tenant rather than the (anonymous, unfiltered) caller.

Sets `X-RateLimit-Limit`/`X-RateLimit-Remaining` on every authenticated request, allowed or not. On a
rejection it also sets `Retry-After`, increments a `rate_limit.rejections` counter tagged by **plan, not
tenant id** (a per-tenant metric label is unbounded cardinality — a real liability once there are more than
a handful of tenants; plan is a small fixed set — same cardinality reasoning as `urls.created`/
`redirects.served`), and writes the `429` JSON body directly via `ErrorResponseWriter` rather than throwing —
this is filter-level, upstream of where `GlobalExceptionHandler` would ever see it.

## `RateLimitResult` / `RateLimiterBackend`

`RateLimitResult` was pulled out to its own file specifically so `RateLimiterBackend` doesn't need to expose
Bucket4j-specific types (e.g. `ConsumptionProbe`) — both `LocalRateLimiterBackend` and
`RedisRateLimiterBackend` produce the same plain record regardless of their underlying mechanism.
`RateLimitProperties` binds `app.rate-limit.plans.*` keyed by the `RateLimitPlan` **enum** in a `Map`, not
one named field per plan — adding a new plan tier is a config addition (plus the enum constant), not a code
change to `TenantRateLimiterService`'s lookup.
