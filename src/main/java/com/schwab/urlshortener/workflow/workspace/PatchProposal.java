package com.schwab.urlshortener.workflow.workspace;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import java.util.List;

/**
 * A proposed change: whole-file contents for a small set of repository paths.
 * In LLM mode this record IS the structured-output schema, so the model can
 * only express file writes. There is deliberately no field for commands,
 * deletions, renames, permissions, or anything outside the repository tree.
 * Every path and size is then checked by {@link ChangeControlPolicy} before
 * anything is written, and only inside the isolated workspace.
 */
public record PatchProposal(
        @JsonPropertyDescription("One-sentence summary of the change")
        String summary,
        @JsonPropertyDescription("Files to create or fully replace, with repository-relative forward-slash paths")
        List<FileChange> files
) {

    public record FileChange(
            @JsonPropertyDescription("Repository-relative path, e.g. src/main/java/com/schwab/urlshortener/Foo.java")
            String path,
            @JsonPropertyDescription("The complete new file content")
            String content,
            @JsonPropertyDescription("Why this file changes, citing task and acceptance-criterion ids")
            String rationale
    ) {
    }
}
