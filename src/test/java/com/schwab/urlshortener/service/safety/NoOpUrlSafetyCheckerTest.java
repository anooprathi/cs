package com.schwab.urlshortener.service.safety;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NoOpUrlSafetyCheckerTest {

    private final NoOpUrlSafetyChecker checker = new NoOpUrlSafetyChecker();

    @Test
    void isSafe_alwaysReturnsTrue() {
        assertThat(checker.isSafe("https://example.com")).isTrue();
        assertThat(checker.isSafe("https://known-malware-site.example")).isTrue();
        assertThat(checker.isSafe("")).isTrue();
    }
}
