package com.schwab.urlshortener.service.shortcode;

import com.schwab.urlshortener.service.ShortCodeGenerator;
import com.schwab.urlshortener.util.Base62Encoder;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;

/**
 * Default strategy: a random (not sequential/id-derived) Base62 code of
 * fixed length, so codes aren't enumerable — see UrlMapping's Javadoc for
 * why enumerability matters for a public redirect service.
 */
@Component
public class RandomBase62ShortCodeGenerator implements ShortCodeGenerator {

    // Increased from 7 to 10 following a production review's point about
    // collision frequency at real volume: 62^7 (~3.5 trillion) sounds
    // large in isolation, but the birthday-paradox collision rate climbs
    // fast well before a keyspace is "full" — at real production link
    // volumes over years of operation, 7 characters starts making
    // MAX_GENERATION_ATTEMPTS retries (see UrlShortenerServiceImpl)
    // meaningfully more frequent than at prototype scale. 62^10
    // (~8.4 * 10^17) pushes that threshold far out with no format change
    // needed elsewhere — ShortCodeFormat.CHARSET_AND_LENGTH already
    // accepts 4-20 characters, so this fits the existing bounds custom
    // aliases and route matching already enforce.
    private static final int CODE_LENGTH = 10;

    private final SecureRandom secureRandom = new SecureRandom();

    @Override
    public String generateCandidate() {
        // Build directly from the alphabet rather than encoding a random long,
        // guaranteeing exactly CODE_LENGTH characters regardless of leading-zero digits.
        long max = 1;
        for (int i = 0; i < CODE_LENGTH; i++) {
            max *= 62;
        }
        long value = (long) (secureRandom.nextDouble() * max);
        String encoded = Base62Encoder.encode(value);
        return "0".repeat(Math.max(0, CODE_LENGTH - encoded.length())) + encoded;
    }
}
