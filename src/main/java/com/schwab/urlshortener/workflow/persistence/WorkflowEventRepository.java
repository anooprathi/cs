package com.schwab.urlshortener.workflow.persistence;

import org.springframework.data.repository.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Deliberately NOT a JpaRepository: it exposes save (insert) and reads only,
 * with no delete, deleteAll, or saveAll-over-existing. Append-only is the
 * interface's shape, not just a convention. (The entity's {@code @PreUpdate}
 * guard catches an attempt to re-save a loaded event.)
 */
public interface WorkflowEventRepository extends Repository<WorkflowEvent, Long> {

    WorkflowEvent save(WorkflowEvent event);

    List<WorkflowEvent> findByRunIdOrderBySequenceAsc(Long runId);

    Optional<WorkflowEvent> findFirstByRunIdOrderBySequenceDesc(Long runId);

    List<WorkflowEvent> findAllByOrderByIdAsc();

    long countByType(WorkflowEvent.Type type);
}
