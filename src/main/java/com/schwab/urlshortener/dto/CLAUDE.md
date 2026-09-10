# CLAUDE.md

Guidance for Claude Code when working in `src/main/java/com/schwab/urlshortener/dto/`.

All request/response types are Java records with Bean Validation annotations directly on the record
components (`@NotBlank`, `@Pattern`, `@Size`, `@Future`) — add a new field the same way, not via a separate
validator class. `ShortenUrlRequest.customAlias`'s pattern and `RedirectController`'s path-matching regex
both reference `util.ShortCodeFormat` rather than duplicating the charset/length literal — do the same for
any new alias-shaped field.

- **`ErrorResponse`** is the shape `GlobalExceptionHandler` (in `exception/`) populates — this package only
  defines the envelope, `exception/` is where it's actually filled in for each failure type.
- **`ErrorResponseWriter`** exists only because some error paths (`ApiKeyAuthenticationFilter`,
  `RateLimitFilter`, `SecurityConfig`'s entry points) run inside the security filter chain, before Spring MVC
  message-converter dispatch, and so can't use the app's `@Autowired` Jackson `ObjectMapper` bean. It owns a
  private, minimal mapper instead — if `ErrorResponse` ever gains a field that needs custom serialization,
  that logic needs to be added here too, not just to the app's main Jackson config.
- **`UpdateUrlRequest`**: both fields are optional (null = "leave unchanged"), but "at least one required" is
  enforced in `UrlShortenerServiceImpl`, not here — a single-field annotation can't express a
  multi-field constraint like that. There is no way to *clear* an already-set `expiresAt` through this
  endpoint; null always means "don't touch it."
- **`PageResponse<T>`** deliberately isn't Spring Data's `Page<T>` returned directly — that type's JSON shape
  has changed across Spring Data versions and exposes paging internals callers don't need. Use
  `PageResponse.from(page)` for any new paginated endpoint rather than returning `Page<T>`.
