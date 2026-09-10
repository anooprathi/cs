# CLAUDE.md

Guidance for Claude Code when working in `src/main/java/com/schwab/urlshortener/exception/`.

**Adding a new "business rule violated, return status X with this message" exception**: extend `ApiException`
(abstract, carries an `HttpStatus` + message) — it's caught by a single handler
(`GlobalExceptionHandler.handleApiException`), so no new handler method is needed. Current subclasses and
their status:
- `DuplicateAliasException`, `DuplicateInvoiceException`, `DuplicateTenantNameException`,
  `DuplicateCustomDomainException` → `409 CONFLICT`
- `InvalidBillingPeriodException`, `InvalidUrlException` → `400 BAD_REQUEST`
- `InvoiceNotFoundException`, `TenantNotFoundException`, `UrlNotFoundException` → `404 NOT_FOUND`
- `UrlExpiredException` → `410 GONE`

Only extend `RuntimeException` directly (bypassing `ApiException`) if the exception needs behavior beyond
"status + message" — the two existing cases are `ShortCodeGenerationException` (its client-facing message
must differ from its internal one, which includes a retry-attempt count that shouldn't leak) and
`RateLimitExceededException` (needs an extra `Retry-After` header). Both keep their own
`@ExceptionHandler` method in `GlobalExceptionHandler` for that reason — follow that pattern, not
`ApiException`, if a new exception needs extra response behavior.

`UrlNotFoundException.forTenant(shortCode)` is a distinct factory (private secondary constructor) from the
default constructor — same class, different message, because "no active mapping" (public redirect/stats path)
and "nothing owned by this tenant at all" (update/reactivate path, where an inactive-but-owned link is a
valid find) are different failure semantics that would be misleading if conflated.

`GlobalExceptionHandler` is the only place ANY of this gets turned into an HTTP response — the full mapping
list (beyond the `ApiException` family above): `MethodArgumentNotValidException` → `400` with per-field
details; `HttpMessageNotReadableException` (malformed JSON) → `400`; `MethodArgumentTypeMismatchException`
(wrong path/query param type) → `400`; `PropertyReferenceException` (invalid `?sort=` value on a
`Pageable`-backed endpoint) → `400`, with the message listing every actual sortable field on the target
entity via reflection, so it can never drift out of sync with the entity; `IllegalArgumentException` → `400`;
`NoResourceFoundException` (no route matched at all) → `404`; everything else → generic `500` that never
leaks the real exception message/stack trace to the client (logged server-side only). A new exception type
that should map to a status needs either an `ApiException` subclass or a new handler here — there is no other
place error mapping happens.
