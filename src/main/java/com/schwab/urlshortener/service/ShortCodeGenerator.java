package com.schwab.urlshortener.service;

/**
 * Produces a single short-code candidate. Deliberately knows nothing
 * about uniqueness or persistence — "try N times against the repository,
 * give up if exhausted" is collision-retry *policy*, which stays in
 * UrlShortenerServiceImpl; this interface is just the *mechanism* for
 * proposing a candidate, and is swappable (e.g. a future vanity/prefix
 * strategy) without touching that policy.
 */
public interface ShortCodeGenerator {
    String generateCandidate();
}
