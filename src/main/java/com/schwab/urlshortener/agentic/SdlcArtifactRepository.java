package com.schwab.urlshortener.agentic;

import com.schwab.urlshortener.agentic.model.SdlcStage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SdlcArtifactRepository extends JpaRepository<SdlcArtifact, Long> {

    List<SdlcArtifact> findByRunIdOrderByIdAsc(Long runId);

    Optional<SdlcArtifact> findByRunIdAndStage(Long runId, SdlcStage stage);
}
