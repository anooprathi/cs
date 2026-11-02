package com.schwab.urlshortener.workflow.persistence;

import com.schwab.urlshortener.workflow.engine.RunStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface WorkflowRunRepository extends JpaRepository<WorkflowRun, Long> {

    List<WorkflowRun> findByStatus(RunStatus status);
}
