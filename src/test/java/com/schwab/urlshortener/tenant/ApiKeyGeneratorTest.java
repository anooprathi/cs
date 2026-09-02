package com.schwab.urlshortener.tenant;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ApiKeyGeneratorTest {

    @Test
    void generate_producesKeyWithExpectedPrefix() {
        String key = ApiKeyGenerator.generate();
        assertThat(key).startsWith("usk_");
        assertThat(key.length()).isGreaterThan(20);
    }

    @Test
    void generate_producesDistinctKeysEachCall() {
        String a = ApiKeyGenerator.generate();
        String b = ApiKeyGenerator.generate();
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void hash_isDeterministic() {
        String key = "usk_fixed-value";
        assertThat(ApiKeyGenerator.hash(key)).isEqualTo(ApiKeyGenerator.hash(key));
    }

    @Test
    void hash_differsForDifferentKeys() {
        assertThat(ApiKeyGenerator.hash("usk_a")).isNotEqualTo(ApiKeyGenerator.hash("usk_b"));
    }

    @Test
    void hash_isHexEncodedSha256Length() {
        // SHA-256 -> 32 bytes -> 64 hex characters
        assertThat(ApiKeyGenerator.hash("usk_anything")).hasSize(64);
    }
}
