package com.schwab.urlshortener.service.shortcode;

import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class RandomBase62ShortCodeGeneratorTest {

    private final RandomBase62ShortCodeGenerator generator = new RandomBase62ShortCodeGenerator();

    @RepeatedTest(20)
    void generateCandidate_isSevenCharsFromTheBase62Alphabet() {
        String candidate = generator.generateCandidate();
        assertThat(candidate).hasSize(10);
        assertThat(candidate).matches("[0-9A-Za-z]{10}");
    }

    @Test
    void generateCandidate_producesVariedOutput_notAConstant() {
        Set<String> candidates = new HashSet<>();
        for (int i = 0; i < 50; i++) {
            candidates.add(generator.generateCandidate());
        }
        // Collisions in 50 draws from a 62^10 keyspace are astronomically
        // unlikely — this is really asserting "not returning the same
        // value every time," not testing true randomness quality.
        assertThat(candidates).hasSizeGreaterThan(45);
    }
}
