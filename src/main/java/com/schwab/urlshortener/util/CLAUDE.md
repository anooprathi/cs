# CLAUDE.md

Guidance for Claude Code when working in `src/main/java/com/schwab/urlshortener/util/`.

- **`Base62Encoder`**: alphabet is `0-9A-Za-z` (in that order — digits first) — no `+`/`/`/`=` as in Base64,
  since those aren't URL-safe. Encodes/decodes non-negative `long`s only; consumed by
  `service/shortcode/RandomBase62ShortCodeGenerator` (a different package) to turn generated values into
  compact codes. This class is pure encoding math with no knowledge of short-code length or charset
  constraints — those live in `ShortCodeFormat` below.
- **`ShortCodeFormat`**: the single source of truth for the short-code/custom-alias shape — `[A-Za-z0-9_-]{4,20}`.
  Two constants, both required to stay compile-time-constant string literals (not derived at runtime) because
  they're consumed as annotation attributes in two different contexts: `CHARSET_AND_LENGTH` (unanchored)
  embeds into `RedirectController`'s `@GetMapping` path-pattern regex and `SecurityConfig`'s request matcher;
  `ANCHORED` (`^...$`) is used in `ShortenUrlRequest`'s `@Pattern` validation. This class exists specifically
  because that literal used to be duplicated across all three call sites — if you need the charset/length
  rule anywhere else, reference one of these two constants rather than writing the regex again, or routing
  and validation will silently drift apart the way they did before.
