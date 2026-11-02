package com.schwab.urlshortener.workflow.persistence;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * "Output artifact X was produced from input artifact Y at this exact version
 * and content hash." Edges are only written for inputs the producing task
 * actually read (AgentRequest records reads), not everything it was offered.
 * The hash is copied onto the edge so lineage stays checkable even if someone
 * questions what that version contained.
 */
@Entity
@Table(name = "workflow_lineage", indexes = {
        @Index(name = "idx_workflow_lineage_output", columnList = "outputArtifactId"),
        @Index(name = "idx_workflow_lineage_input", columnList = "inputArtifactId")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WorkflowLineageEdge {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long runId;

    @Column(nullable = false)
    private Long outputArtifactId;

    @Column(nullable = false)
    private Long inputArtifactId;

    @Column(nullable = false, length = 100)
    private String inputName;

    private int inputVersion;

    @Column(nullable = false, length = 64)
    private String inputHash;
}
