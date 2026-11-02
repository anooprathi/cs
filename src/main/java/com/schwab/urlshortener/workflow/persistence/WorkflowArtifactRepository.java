package com.schwab.urlshortener.workflow.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface WorkflowArtifactRepository extends JpaRepository<WorkflowArtifact, Long> {

    List<WorkflowArtifact> findByRunIdOrderByIdAsc(Long runId);

    List<WorkflowArtifact> findByRunIdAndNameOrderByVersionAsc(Long runId, String name);

    Optional<WorkflowArtifact> findByRunIdAndNameAndVersion(Long runId, String name, int version);

    /** Newest accepted, non-invalidated version produced from exactly these inputs: the reuse candidate. */
    Optional<WorkflowArtifact> findFirstByRunIdAndNameAndInputFingerprintAndAcceptedTrueAndInvalidatedFalseOrderByVersionDesc(
            Long runId, String name, String inputFingerprint);

    @Query("select coalesce(max(a.version), 0) from WorkflowArtifact a where a.runId = ?1 and a.name = ?2")
    int maxVersion(Long runId, String name);
}
