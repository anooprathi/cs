package com.schwab.urlshortener.workflow.agents.recipes;

import com.schwab.urlshortener.agentic.model.RequirementSpec;
import com.schwab.urlshortener.workflow.workspace.PatchProposal;

import java.nio.file.Path;
import java.util.Locale;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * A DETERMINISTIC_DEMO code recipe: a pre-authored, rule-based code change for
 * one recognized requirement, applied by anchored edits to the working tree's
 * actual source. It produces real product code, tests, and an interface contract,
 * so the demo exercises the whole pipeline (worktree, validation commands, real
 * `mvn test`, review, promotion) without a model.
 *
 * It is NOT general code generation and NOT AI. A recipe implements exactly the
 * change it was written for, and only when the requirement matches. Every other
 * requirement gets the change-record fallback in demo mode, and general code
 * changes need LLM mode.
 */
public interface CodeRecipe {

    String id();

    String description();

    /** Whether the normalized requirement asks for exactly this change. */
    boolean matches(RequirementSpec spec);

    /**
     * Builds the patch against the tree at {@code root}.
     *
     * @throws SourceEdits.RecipeNotApplicable if the code doesn't look like what the recipe was written for
     */
    PatchProposal apply(Path root);

    /** All FR/NFR statement text of the spec, lower-cased, for matching. */
    static String statements(RequirementSpec spec) {
        Stream<RequirementSpec.Statement> all = Stream.concat(
                spec.functionalRequirements() == null ? Stream.empty() : spec.functionalRequirements().stream(),
                spec.nonFunctionalRequirements() == null ? Stream.empty() : spec.nonFunctionalRequirements().stream());
        return all.map(RequirementSpec.Statement::text).collect(Collectors.joining("\n")).toLowerCase(Locale.ROOT);
    }
}
