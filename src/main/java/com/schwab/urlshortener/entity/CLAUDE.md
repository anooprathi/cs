# CLAUDE.md

Guidance for Claude Code when working in `src/main/java/com/schwab/urlshortener/entity/`.

One entity, `UrlMapping`. Notable fields/constraints beyond what's in the root doc:
- `shortCode`: `unique = true`, `length = 20`, backed by `idx_short_code` — this DB constraint, not the
  service-layer `existsByShortCode` pre-check, is the real uniqueness guarantee (see root CLAUDE.md's
  concurrency/ACID note).
- `originalUrl`: indexed (`idx_original_url`) to support duplicate-detection/idempotent-lookup queries
  (`findFirstByOriginalUrlAndActiveTrueAndExpiresAtIsNull` in `repository/`) without a full table scan —
  despite that, there is currently no uniqueness constraint on it, so the same destination can be shortened
  more than once (see README.md §11 "Duplicate-URL detection... not implemented").
- `tenantId`: a plain indexed `Long`, not a JPA `@ManyToOne` relationship / real FK to a `Tenant` row — a
  deliberate simplicity trade-off, not an oversight (see `ARCHITECTURE.md`'s design-decision table and README
  §10 "Data & persistence" for the retrofitting caveat if this ever changes).
- `clickCount`: mutated ONLY via `UrlMappingRepository.incrementClickCount`'s atomic `UPDATE`, never via
  `setClickCount` + `save()` — the Lombok `@Setter` exists for JPA/Builder mechanics, not as the intended
  mutation path for this field. Don't reintroduce a read-modify-write on it.
- `active` / `expiresAt`: soft-delete and expiry are two independent flags, not derived from each other —
  `isExpired()` only checks `expiresAt`, so an expired-but-still-`active=true` row is a valid, meaningful
  state (it's what makes `410 Gone` vs `404` distinguishable at the service layer).
- `customAlias` (boolean): records whether the code was caller-supplied vs system-generated, kept purely for
  analytics/reporting — not read anywhere in authorization or lookup logic.
