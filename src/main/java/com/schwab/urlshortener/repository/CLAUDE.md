# CLAUDE.md

Guidance for Claude Code when working in `src/main/java/com/schwab/urlshortener/repository/`.

One repository, `UrlMappingRepository`. Its method set IS the interesting content here — each exists for a
specific caller with different semantics, don't collapse them:
- `findByShortCodeAndActiveTrue` — public redirect/stats lookup (no tenant scoping; the redirect path is
  tenant-agnostic by design).
- `findByShortCodeAndActiveTrueAndTenantId` — tenant-scoped, active-only. Used where "not active" should read
  as "not found" (e.g. stats/deactivate on a link the tenant doesn't currently see as live).
- `findByShortCodeAndTenantId` — tenant-scoped, ignores active status. Used by update/reactivate, where
  finding an inactive-but-owned row is the expected, valid case, not a not-found case.
- `existsByShortCode` — fast-fail pre-check only, NOT the real uniqueness guarantee (that's the DB unique
  constraint on `shortCode` — see `entity/CLAUDE.md`). Don't rely on this for correctness under concurrency.
- `findFirstByOriginalUrlAndActiveTrueAndExpiresAtIsNull` — idempotent-reshortening/duplicate-lookup support;
  only matches links with no expiry set, since a lookup that could return an about-to-expire match would be
  surprising.
- `incrementClickCount` (`@Modifying @Query`) — the only sanctioned way to mutate `clickCount`; a single
  atomic `UPDATE`, not a load-then-save, specifically to avoid a read-modify-write race under concurrent hits
  to the same popular short code.
- `findByExpiresAtBeforeAndActiveTrue` — used by `ExpiredUrlCleanupService`'s scheduled sweep; loads full rows
  rather than a paged/bulk `UPDATE` (see README §10 "Scalability & reliability" for the known scaling gap
  here if this table ever gets large).
- `findByTenantId` (paginated) / `countByTenantId` — cross-tenant admin visibility and the tenant's own "list
  my links" endpoint both go through the paginated variant; sort order comes from the caller's `Pageable`
  rather than being baked into a second, near-identical method.

If you need a new query, check whether one of the tenant-scoping variants above already fits before adding
another `findByShortCodeAnd...` method — the active/tenant-scoping combination is the axis that actually
varies here, not the underlying lookup.
