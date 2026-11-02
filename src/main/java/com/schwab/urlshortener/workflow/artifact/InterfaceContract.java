package com.schwab.urlshortener.workflow.artifact;

import java.util.List;

/**
 * The IMPLEMENTATION stage's API and schema definition for one change: every
 * endpoint and payload field that changes, and every table/column change with
 * the DDL a real migration would need (this project uses Hibernate ddl-auto, so
 * the DDL is the reviewable record of the schema change, not something the app
 * runs itself).
 *
 * {@code source} says how exact it is:
 *  - RECIPE: written by a deterministic demo code recipe alongside its code, so it matches the patch exactly;
 *  - MODEL: declared by the model alongside a model-authored patch (review it against the diff);
 *  - DERIVED_PROPOSAL: no code was generated, so it's derived from the design: which components need a contract
 *    change, without exact fields.
 */
public record InterfaceContract(Source source, String summary, List<ApiChange> apiChanges, List<SchemaChange> schemaChanges) {

    public enum Source {
        RECIPE,
        MODEL,
        DERIVED_PROPOSAL
    }

    public InterfaceContract {
        apiChanges = apiChanges == null ? List.of() : List.copyOf(apiChanges);
        schemaChanges = schemaChanges == null ? List.of() : List.copyOf(schemaChanges);
    }

    /** One endpoint-level change, e.g. POST /api/v1/urls gains request field maxClicks (integer, optional, min 1). */
    public record ApiChange(String method, String path, String change, String detail) {
    }

    /** One persistence change with its migration DDL, e.g. ALTER TABLE url_mapping ADD COLUMN max_clicks INTEGER. */
    public record SchemaChange(String table, String column, String change, String ddl) {
    }
}
