package com.schwab.urlshortener.agentic;

import com.schwab.urlshortener.agentic.ComponentCatalog.CatalogComponent;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The catalog is curated by hand, so its failure mode is drift: a class
 * gets renamed or moved and impact analysis keeps confidently naming the
 * old one. These tests turn that drift into a build failure. Maven runs
 * tests with the project root as the working directory, which is what the
 * relative paths are resolved against.
 */
class ComponentCatalogTest {

    private final ComponentCatalog catalog = AgenticFixtures.catalog();

    @Test
    void everyCatalogPath_existsInThisRepository() {
        List<String> missing = catalog.all().stream()
                .map(CatalogComponent::path)
                .filter(p -> !Files.exists(Path.of(p)))
                .toList();
        assertThat(missing).as("catalog paths that no longer exist").isEmpty();
    }

    @Test
    void everyListedTestFile_existsInThisRepository() {
        List<String> missing = catalog.all().stream()
                .flatMap(c -> c.tests().stream())
                .filter(p -> !Files.exists(Path.of(p)))
                .toList();
        assertThat(missing).as("catalog test files that no longer exist").isEmpty();
    }

    @Test
    void everyComponent_hasLayerResponsibilityAndKeywords() {
        assertThat(catalog.size()).isGreaterThan(20);
        for (CatalogComponent c : catalog.all()) {
            assertThat(c.layer()).as(c.name()).isNotNull();
            assertThat(c.responsibility()).as(c.name()).isNotBlank();
            assertThat(c.keywords()).as(c.name()).isNotEmpty();
            assertThat(c.tests()).as(c.name()).isNotEmpty();
        }
    }

    @Test
    void lookup_byExactName() {
        assertThat(catalog.contains("UrlShortenerServiceImpl")).isTrue();
        assertThat(catalog.contains("UrlShortenerServiceImplV2")).isFalse();
        assertThat(catalog.contains(null)).isFalse();
    }

    @Test
    void duplicateNames_areRejected() {
        CatalogComponent c = new CatalogComponent("X", "p", ComponentCatalog.Layer.DATA, "r", List.of("k"), List.of("t"));
        assertThatThrownBy(() -> new ComponentCatalog(List.of(c, c)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate component name");
    }
}
