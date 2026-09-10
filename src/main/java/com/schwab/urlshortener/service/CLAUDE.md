# CLAUDE.md

Guidance for Claude Code when working in `src/main/java/com/schwab/urlshortener/service/`.

Covers this package and its subpackages `impl/`, `safety/`, `shortcode/` — small and tightly related, so
one file rather than four thin ones.

## Interface/impl split (the pattern repeats three times here)

Three interfaces in this package each separate *mechanism* from *policy*, with the policy living in
`UrlShortenerServiceImpl` and the mechanism swappable underneath it:

- `ShortCodeGenerator.generateCandidate()` — produces one candidate, knows nothing about uniqueness. The
  retry policy (`MAX_GENERATION_ATTEMPTS = 5`, a hardcoded constant, not a `@ConfigurationProperties` value)
  and what happens on exhaustion (`ShortCodeGenerationException`) live in
  `UrlShortenerServiceImpl.generateUniqueCode`, not in the generator.
- `UrlSafetyChecker.isSafe(url)` — two implementations, selected at startup by Spring, not re-branched per
  call: `FeignUrlSafetyChecker` (`@ConditionalOnProperty(app.url-safety-check.enabled=true)`) and
  `NoOpUrlSafetyChecker` (`havingValue="false", matchIfMissing=true`). The conditions are mutually exclusive
  by construction, so no `@Primary` is needed and exactly one bean always exists.
- Same pattern again one package over: `RateLimiterBackend` (see `ratelimit/CLAUDE.md`).

`FeignUrlSafetyChecker` catches `Exception` broadly (not a narrower Feign-specific type) around the client
call and logs at `ERROR` before returning `true` — so *any* failure mode (timeout, 5xx, deserialization)
degrades to fail-open, not just the ones anticipated at write time.

## `UrlShortenerServiceImpl.createShortUrl` — exact sequencing

1. `urlSafetyChecker.isSafe(originalUrl)` — first thing, before any DB interaction. Unsafe → `InvalidUrlException`, nothing persisted.
2. Branch: custom alias (`createWithCustomAlias`) vs generated code (`createWithGeneratedCode`) — both do `existsByShortCode` pre-check, then `save`, then catch `DataIntegrityViolationException` from the DB constraint as the real backstop (root `CLAUDE.md` covers the 409 mapping).
3. `urlsCreatedCounter.increment()`.
4. `usageMeteringService.recordApiCall(tenantId)` — fire-and-forget async, does not block the response.
5. Response built via `mapper.toShortenResponse(saved, resolveBaseUrl(tenantId))`.

`resolveBaseUrl` does a **live, uncached** `tenantService.getTenantOrThrow(tenantId)` read on *every*
creation to check `Tenant.customDomain` — unlike the redirect path (below), creation isn't the hot path, and
a custom-domain change should apply to the very next link a tenant creates, not lag behind a cache TTL. If
you're tuning creation-path performance, this is a real per-request DB read, not a config lookup.

## `resolveAndRecordHit` (redirect hot path) — exact sequencing

Wrapped once in a `Timer.Sample`/`redirect.resolve.duration` (percentile histogram) around everything below.
Inside `doResolveAndRecordHit`:

1. `cachedShortCodeLookup.findActive(shortCode)` — cache lookup, see below. `null` → `UrlNotFoundException`.
2. `target.isExpired()` against the **cached** `expiresAt` — `UrlExpiredException` if past.
3. Rate-limit check — `tenantService.getPlanForRateLimiting(target.tenantId())` then
   `rateLimiterService.tryConsumeRedirectPermit(...)`. **This runs after the cache lookup but before any DB
   write** — a rate-limited hit costs a cache read (and possibly a cache-populating DB read on a miss) but
   never reaches `incrementClickCount`, so throttled traffic doesn't add write load.
4. `repository.incrementClickCount(shortCode, now)` — atomic bulk `UPDATE` (see root `CLAUDE.md`).
5. `redirectsServedCounter.increment()`, then `usageMeteringService.recordRedirect(...)` (async).
6. Return `target.originalUrl()`.

## `CachedShortCodeLookup`

Deliberately its own `@Component`, not a method on `UrlShortenerServiceImpl` — `@Cacheable` is proxy-based,
and a same-class self-invocation would silently bypass caching entirely (the same Spring-proxy gotcha the
root `CLAUDE.md` names for `@Transactional`/`UsageRecordCreator`).

Caches a small immutable projection, `RedirectTarget(tenantId, originalUrl, expiresAt)` — **not** the
`UrlMapping` entity (mutable, would let callers share/corrupt one instance across requests) and **not**
`clickCount`/`lastAccessedAt` (those are written via the bulk `UPDATE` on every hit specifically so a hot
link doesn't serialize through JPA dirty-checking; caching them would go stale immediately anyway).

A miss is **not** cached (`unless = "#result == null"` on `@Cacheable`) — caching negative lookups would let
a scanner probing random codes cheaply pollute the cache, and a falsely-cached "not found" wouldn't
self-correct when a link is created moments later.

Eviction is explicit from exactly three call sites, all of which must take effect immediately rather than
wait out the cache TTL:
- `UrlShortenerServiceImpl.deactivate`
- `UrlShortenerServiceImpl.updateUrl` — evicts **regardless of whether the link is currently active**, since
  a stale destination/expiry must not survive an edit.
- `ExpiredUrlCleanupService.deactivateExpiredMappings` (the scheduled sweep, below).

`reactivate` deliberately does **not** evict — since misses are never cached, there's nothing stale to clear
for a code that was inactive a moment ago. The cache's TTL (`CacheConfig`, 2 minutes) is a backstop for any
path other than these three, not the primary invalidation mechanism.

## `ExpiredUrlCleanupService`

`@Scheduled(fixedDelay = 10 * 60 * 1000)` — every 10 minutes, hardcoded (not a property), `fixedDelay` (not
`fixedRate`) so a slow run can't overlap the next one. Mechanism: `findByExpiresAtBeforeAndActiveTrue` loads
**every** expired-but-still-active row into memory, flips `active=false` on each in a Java loop, then
`saveAll`, then evicts each from `CachedShortCodeLookup` one at a time. This is the "load every expired row,
save them all" limitation named in `README.md` §10 (not a paged/bulk `UPDATE`) and has no distributed lock —
correct on a single instance; on multiple instances it would do redundant (not incorrect) work, since
whichever instance's sweep runs first flips the rows and the others just find nothing left to do.

## `UrlMappingMapper`

Pure entity→DTO shaping, split out of `UrlShortenerServiceImpl` specifically so that class's job stays
"business rules," not also "wire format." Genuinely trivial today (two straight field-copies) — kept as its
own seam for when the response shape needs to diverge from the entity (e.g. a v2 response, HATEOAS links),
not because it currently does anything non-obvious.

## `RandomBase62ShortCodeGenerator`

`CODE_LENGTH = 10` (raised from an original 7 after a review flagged birthday-paradox collision frequency at
real production volume — see the class's own comment for the exact reasoning). Builds each of the 10
characters independently via `SecureRandom.nextInt(62)` rather than generating one random `long` and running
it through `Base62Encoder.encode`: `62^10` (~8.4×10^17) exceeds a `double`'s 53-bit mantissa, so scaling
`nextDouble()` up to that range would make some encoded values unreachable and others over-represented — a
real non-uniformity, not a style choice. If you change `CODE_LENGTH` again, this per-character approach is
why — don't "simplify" it back to a single scaled random number without re-deriving that math.
