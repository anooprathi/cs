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
        // Per-character nextInt(62), not nextDouble() * 62^10 fed through
        // Base62Encoder.encode(long): 62^10 (~8.39 * 10^17) exceeds a
        // double's 53-bit mantissa (~9.01 * 10^15), so scaling nextDouble()
        // (itself only 53 bits of randomness) up to that range can't
        // address every long value with equal probability — some values
        // become unreachable and others land more often than others, a
        // real non-uniformity in the code space, not just a style
        // preference. Drawing each character independently from
        // SecureRandom.nextInt(62) has no such range/precision mismatch —
        // every character is uniform over the alphabet on its own — and
        // it also drops the encode-then-zero-pad step entirely, since
        // building CODE_LENGTH characters directly always yields exactly
        // CODE_LENGTH characters.
        StringBuilder sb = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) {
            sb.append(Base62Encoder.ALPHABET.charAt(secureRandom.nextInt(62)));
        }
        return sb.toString();
    }
}
