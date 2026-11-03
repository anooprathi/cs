package com.schwab.urlshortener.workflow.agents.recipes;

import com.schwab.urlshortener.agentic.model.RequirementSpec;
import com.schwab.urlshortener.workflow.artifact.InterfaceContract;
import com.schwab.urlshortener.workflow.workspace.ChangePatch;
import com.schwab.urlshortener.workflow.workspace.PatchProposal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The deterministic demo recipes against THIS repository's real source (tests run from the project root), plus
 * the anchored-edit and codemod mechanics. That the patched tree compiles and its tests pass is proven end to
 * end by ScenarioEndToEndTest, which runs the real test command in a worktree.
 */
class CodeRecipesTest {

    private static final Path REPO = Path.of(".");

    private static RequirementSpec spec(String... statements) {
        List<RequirementSpec.Statement> frs = new java.util.ArrayList<>();
        for (int i = 0; i < statements.length; i++) {
            frs.add(new RequirementSpec.Statement("FR-" + (i + 1), statements[i]));
        }
        return new RequirementSpec("t", "s", frs, List.of(), List.of(), List.of(), List.of());
    }

    private static Map<String, String> files(PatchProposal p) {
        return p.files().stream().collect(Collectors.toMap(PatchProposal.FileChange::path, PatchProposal.FileChange::content));
    }

    @Test
    void clickLimit_matchesItsRequirementOnly() {
        assertThat(new ClickLimitRecipe().matches(spec("Tenants may set an optional maximum click limit on a link."))).isTrue();
        assertThat(new ClickLimitRecipe().matches(spec("Make links safer."))).isFalse();
    }

    @Test
    void clickLimit_producesRealCodeTestsAndAnExactContract() {
        PatchProposal p = new ClickLimitRecipe().apply(REPO);
        Map<String, String> files = files(p);

        assertThat(files).containsKeys(ClickLimitRecipe.ENTITY, ClickLimitRecipe.REQUEST, ClickLimitRecipe.STATS, ClickLimitRecipe.MAPPER,
                ClickLimitRecipe.REPOSITORY, ClickLimitRecipe.LOOKUP, ClickLimitRecipe.SERVICE, ClickLimitRecipe.EXCEPTION, ClickLimitRecipe.TEST,
                ClickLimitRecipe.SERVICE_TEST, ClickLimitRecipe.MAPPER_TEST, ClickLimitRecipe.README);
        assertThat(files.get(ClickLimitRecipe.README)).contains("Optional `maxClicks`", "`remainingClicks`", "click limit is reached");
        assertThat(files.get(ClickLimitRecipe.ENTITY)).contains("private Integer maxClicks;");
        assertThat(files.get(ClickLimitRecipe.REQUEST)).contains("@Min(value = 1", "Integer maxClicks\n) {");
        assertThat(files.get(ClickLimitRecipe.REPOSITORY)).contains("AND (u.maxClicks IS NULL OR u.clickCount < u.maxClicks)");
        assertThat(files.get(ClickLimitRecipe.SERVICE)).contains("int counted = repository.incrementClickCount(", ".maxClicks(request.maxClicks())",
                "import com.schwab.urlshortener.exception.ClickLimitReachedException;");
        assertThat(files.values()).allSatisfy(c -> assertThat(c).doesNotContain("\r\n"));
        // codemod: existing test call sites get the new trailing argument
        String serviceTest = files.get("src/test/java/com/schwab/urlshortener/service/UrlShortenerServiceImplTest.java");
        assertThat(serviceTest).isNotNull().doesNotContain("new CachedShortCodeLookup.RedirectTarget(TENANT_ID, \"https://example.com\", null))");
        assertThat(serviceTest).contains("new CachedShortCodeLookup.RedirectTarget(TENANT_ID, \"https://example.com\", null, null))");
        // this file mentions the constructors only inside string literals, so the codemod must leave it alone
        assertThat(files).doesNotContainKey("src/test/java/com/schwab/urlshortener/workflow/agents/recipes/CodeRecipesTest.java");
        assertThat(p.summary()).containsPattern("\\b[1-9][0-9]* existing test call sites updated by codemod");

        assertThat(ChangePatch.kindOf(p.files())).isEqualTo(ChangePatch.Kind.CODE);
        assertThat(ChangePatch.testFilesOf(p.files())).contains(ClickLimitRecipe.TEST, ClickLimitRecipe.SERVICE_TEST, ClickLimitRecipe.MAPPER_TEST);
        assertThat(p.apiChanges()).extracting(InterfaceContract.ApiChange::path).contains("/api/v1/urls", "/{shortCode}");
        assertThat(p.schemaChanges()).singleElement().satisfies(s -> assertThat(s.ddl()).contains("ADD COLUMN max_clicks"));
    }

    @Test
    void expiredReactivation_guardsReactivateAndAddsATest() {
        assertThat(new ExpiredReactivationRecipe().matches(spec(
                "Tenants reactivating a link whose expiry is in the past must receive 409 Conflict."))).isTrue();

        PatchProposal p = new ExpiredReactivationRecipe().apply(REPO);
        Map<String, String> files = files(p);

        assertThat(files).containsOnlyKeys(ExpiredReactivationRecipe.SERVICE, ExpiredReactivationRecipe.EXCEPTION, ExpiredReactivationRecipe.TEST,
                ExpiredReactivationRecipe.SERVICE_TEST, ExpiredReactivationRecipe.README);
        assertThat(files.get(ExpiredReactivationRecipe.README)).contains("`409` if the link's expiry has passed");
        assertThat(files.get(ExpiredReactivationRecipe.SERVICE)).contains("if (mapping.isExpired()) {\n            throw new ExpiredLinkReactivationException");
        assertThat(files.get(ExpiredReactivationRecipe.EXCEPTION)).contains("HttpStatus.CONFLICT");
        assertThat(p.apiChanges()).singleElement().satisfies(a -> assertThat(a.change()).isEqualTo("RESPONSE_STATUS_ADDED"));
        assertThat(p.schemaChanges()).isEmpty();
    }

    @Test
    void registry_skipsARecipeThatDoesNotFitTheTree(@TempDir Path empty) {
        CodeRecipes recipes = new CodeRecipes();
        RequirementSpec clickLimit = spec("Tenants may set an optional maximum click limit.");

        assertThat(recipes.apply(clickLimit, empty)).isEmpty();
        assertThat(recipes.apply(spec("Make links safer."), REPO)).isEmpty();
        assertThat(recipes.apply(clickLimit, REPO)).hasValueSatisfying(a -> assertThat(a.recipe().id()).isEqualTo("click-limit"));
        assertThat(recipes.apply(null, REPO)).isEmpty();
    }

    @Test
    void replaceOnce_refusesMissingOrAmbiguousAnchors(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("A.java"), "x x\r\ny\r\n");
        SourceEdits edits = new SourceEdits(dir);

        assertThatThrownBy(() -> edits.replaceOnce("A.java", "x", "z")).hasMessageContaining("not unique");
        assertThatThrownBy(() -> edits.replaceOnce("A.java", "q", "z")).hasMessageContaining("not found");
        assertThatThrownBy(() -> edits.read("Missing.java")).isInstanceOf(SourceEdits.RecipeNotApplicable.class);
        edits.replaceOnce("A.java", "y\n", "w\n");
        assertThat(edits.edits().get("A.java")).isEqualTo("x x\nw\n");
        assertThatThrownBy(() -> edits.create("A.java", "")).hasMessageContaining("already exists");
    }

    @Test
    void codemod_handlesNestingStringsAndEmptyArgumentLists(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("src/test/java"));
        Files.writeString(dir.resolve("src/test/java/T.java"),
                "a(new R(f(1, 2), \"x, ) y\", ')'));\nb(new R());\nnew R(\n    1, // a ) comment\n    g(h()));\nc(new R(new R(1)));\n"
                        + "String s = \"new R(1)\"; /* new R(2) */ // new R(3)\nString t = \"\"\"\n  new R(4)\n  \"\"\";\nrenew R(5);\n");
        Files.writeString(dir.resolve("src/test/java/Skip.java"), "new R(1);");
        Files.writeString(dir.resolve("src/test/java/OnlyStrings.java"), "String s = \"new R(1)\";");
        SourceEdits edits = new SourceEdits(dir);

        int changed = edits.appendConstructorArgument("src/test/java", "R", "null", List.of("src/test/java/Skip.java"));

        assertThat(changed).isEqualTo(5);
        assertThat(edits.edits().get("src/test/java/T.java"))
                .isEqualTo("a(new R(f(1, 2), \"x, ) y\", ')', null));\nb(new R(null));\nnew R(\n    1, // a ) comment\n    g(h()), null);\n"
                        + "c(new R(new R(1, null), null));\n"
                        + "String s = \"new R(1)\"; /* new R(2) */ // new R(3)\nString t = \"\"\"\n  new R(4)\n  \"\"\";\nrenew R(5);\n");
        assertThat(edits.edits()).doesNotContainKeys("src/test/java/Skip.java", "src/test/java/OnlyStrings.java");
        assertThat(edits.appendConstructorArgument("src/nope", "R", "null", List.of())).isZero();
    }
}
