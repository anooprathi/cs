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

    private static final int CODE_LENGTH = 7;

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
