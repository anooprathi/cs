package com.schwab.urlshortener.workflow.agents.recipes;

import com.schwab.urlshortener.agentic.model.RequirementSpec;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * The fixed catalog of deterministic demo code recipes. The first recipe that
 * matches the requirement and applies cleanly to the tree wins. A match whose
 * anchors don't fit the tree is logged and skipped, so the agent falls back to a
 * change record. A recipe never half-applies.
 */
@Component
@Slf4j
public class CodeRecipes {

    public record Applied(CodeRecipe recipe, com.schwab.urlshortener.workflow.workspace.PatchProposal proposal) {
    }

    private final List<CodeRecipe> recipes;

    public CodeRecipes() {
        this(List.of(new ClickLimitRecipe(), new ExpiredReactivationRecipe()));
    }

    CodeRecipes(List<CodeRecipe> recipes) {
        this.recipes = List.copyOf(recipes);
    }

    public List<CodeRecipe> all() {
        return recipes;
    }

    public Optional<Applied> apply(RequirementSpec spec, Path tree) {
        if (spec == null || tree == null) {
            return Optional.empty();
        }
        for (CodeRecipe recipe : recipes) {
            if (!recipe.matches(spec)) {
                continue;
            }
            try {
                return Optional.of(new Applied(recipe, recipe.apply(tree)));
            } catch (SourceEdits.RecipeNotApplicable e) {
                log.warn("Demo code recipe '{}' matched the requirement but does not apply to this tree: {}", recipe.id(), e.getMessage());
            }
        }
        return Optional.empty();
    }
}
