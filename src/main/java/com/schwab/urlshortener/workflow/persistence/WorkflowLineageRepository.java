package com.schwab.urlshortener.workflow.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface WorkflowLineageRepository extends JpaRepository<WorkflowLineageEdge, Long> {

    List<WorkflowLineageEdge> findByOutputArtifactIdOrderByIdAsc(Long outputArtifactId);

    List<WorkflowLineageEdge> findByInputArtifactIdOrderByIdAsc(Long inputArtifactId);
}
