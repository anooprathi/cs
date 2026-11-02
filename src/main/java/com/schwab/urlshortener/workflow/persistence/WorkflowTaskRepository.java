package com.schwab.urlshortener.workflow.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface WorkflowTaskRepository extends JpaRepository<WorkflowTask, Long> {

    List<WorkflowTask> findByRunIdOrderByIdAsc(Long runId);

    Optional<WorkflowTask> findByRunIdAndTaskKey(Long runId, String taskKey);
}
