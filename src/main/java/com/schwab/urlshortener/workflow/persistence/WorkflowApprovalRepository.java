package com.schwab.urlshortener.workflow.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface WorkflowApprovalRepository extends JpaRepository<WorkflowApproval, Long> {

    List<WorkflowApproval> findByRunIdOrderByIdAsc(Long runId);

    List<WorkflowApproval> findByRunIdAndTaskKeyAndStatus(Long runId, String taskKey, WorkflowApproval.Status status);

    List<WorkflowApproval> findByArtifactIdAndStatus(Long artifactId, WorkflowApproval.Status status);
}
