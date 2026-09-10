# CLAUDE.md

Guidance for Claude Code when working in `src/main/java/com/schwab/urlshortener/tenant/`.

**API keys are hashed with SHA-256, not BCrypt** (`ApiKeyGenerator`, used by `Tenant.apiKeyHash`) —
deliberate, not an oversight. Keys are high-entropy, machine-generated secrets (32 random bytes,
base64url-encoded), not low-entropy user passwords, so they don't need BCrypt's deliberately-slow
per-guess cost to resist offline brute-forcing. A fast deterministic hash also allows a direct indexed
lookup (`findByApiKeyHashAndActiveTrue`) instead of scanning and comparing against every stored hash,
which BCrypt's random-salt-per-hash design would force. The raw key is surfaced exactly once (at
registration via `TenantRegistrationResponse`, or at rotation via `TenantApiKeyRotationResult`) and never
logged or persisted in that form — grep for `log.info` in `TenantService` to confirm any change here keeps
that invariant.

**`name` vs `normalizedName`**: `Tenant.name` is the caller's exact display string and has no DB
uniqueness constraint; `normalizedName` (trimmed, lowercased, computed once in `TenantService.register`)
carries the actual unique index, so `"Acme Corp"` and `"ACME CORP"` collide as a human would expect. If you
add another tenant-identifying field that needs case/whitespace-insensitive uniqueness, follow this same
split rather than adding a constraint directly on the display column.

**Two "not found" paths with different HTTP semantics** — don't collapse them:
- `getTenantById(id)` — id is caller-supplied input (e.g. an admin looking up an arbitrary tenant); missing
  means ordinary client error, throws `TenantNotFoundException` → `404`.
- `getTenantOrThrow(id)` — id comes from an already-authenticated `TenantPrincipal`; missing means the
  authenticated caller's own backing row vanished, an invariant violation, not user input. Throws
  `IllegalStateException` → `500`. Used by call sites (e.g. `InvoiceController`) that need the full
  `Tenant` for the *caller*, not for input the caller supplied.

**Uniqueness checks follow a repeated TOCTOU-safe pattern** — a fast `exists*`/`findBy*` pre-check for UX,
backstopped by catching `DataIntegrityViolationException` from the real DB unique constraint and remapping
it to the same conflict exception the pre-check would throw. This shape appears in both `register`
(`normalizedName`) and `updateCustomDomain` (`customDomain`) — if you add another uniquely-constrained
tenant field, match this pattern rather than trusting the pre-check alone.

**Plan and custom-domain changes are admin-only by the same reasoning, enforced at the service layer, not
just by controller routing**: `TenantRegistrationRequest` has no `plan` field at all (a prior version
accepted a caller-chosen plan — a real vulnerability a production review caught, letting anyone
self-register as PREMIUM for free; see the class's own Javadoc and
`register_alwaysAssignsStandard_thereIsNoWayToRequestOtherwise`). `updateCustomDomain`'s collision check
explicitly excludes the tenant being updated (`filter(existing -> !existing.getId().equals(tenantId))`) —
re-submitting a domain the tenant already owns is a legitimate no-op, not a conflict with "itself."

**`getPlanForRateLimiting` is cached** (`CacheConfig.TENANT_PLANS`, 10-minute TTL) and explicitly evicted by
`updatePlan` via `@CacheEvict` — an admin plan change must not take up to 10 minutes to affect redirect
rate limiting. It falls back to `STANDARD` for a tenant that's vanished between link creation and redirect
— matches the pre-caching behavior; degrading to the more conservative tier is safe, defaulting open is
not. If you add another cached tenant lookup that a mutation can invalidate, evict explicitly the same way
rather than relying on TTL expiry alone.

`listAll()` (unpaginated) and `listAll(Pageable)` both exist and are both used by real callers (the former
by `AdminService.getUsageSummary`'s tenant-count context, the latter by the admin listing endpoint) — the
unpaginated form is not dead code left over from before pagination was added.
