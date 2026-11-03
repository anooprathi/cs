package com.schwab.urlshortener.workflow.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.schwab.urlshortener.workflow.engine.Hashes;
import com.schwab.urlshortener.workflow.persistence.WorkflowEvent;
import com.schwab.urlshortener.workflow.persistence.WorkflowEventRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The orchestrator's only audit writer: an append-only, per-run hash-chained
 * event log.
 *
 * <b>Append-only:</b> the only write operation is {@link #append}. The backing
 * repository has no update or delete methods, and the entity rejects updates.
 *
 * <b>Redaction:</b> message, refs, and details pass through {@link SecretPatterns#redact}
 * before they are hashed or stored, and the application log line gets the same
 * redacted text. Callers never put prompt content or raw requirement text in an
 * entry (hashes and artifact refs only).
 *
 * <b>Tamper evidence:</b> each event stores {@code hash = SHA-256(prevHash + canonical fields)},
 * with {@code prevHash} the previous event of the same run, and a contiguous
 * {@code sequence}. {@link #verify} recomputes the chain and reports the first
 * broken link. Appends for one run are serialized under a per-run lock, and each
 * one commits in its own transaction inside that lock, so the chain can't fork
 * under concurrent task threads.
 *
 * <b>Limits:</b> the chain detects modification, deletion, or reordering of stored
 * events by someone who doesn't recompute every later hash. Anyone with database
 * write access CAN recompute the whole chain, because it is not anchored outside
 * the database (no external timestamping or WORM storage). Single-instance only:
 * the per-run lock is in-process. See docs/governance.md.
 */
@Component
@Slf4j
public class AuditLog {

    public record Verification(Long runId, boolean valid, int eventsChecked, Long firstBrokenSequence, String reason) {
    }

    private final WorkflowEventRepository events;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate tx;
    private final Map<Long, Object> locks = new ConcurrentHashMap<>();

    public AuditLog(WorkflowEventRepository events, ObjectMapper objectMapper, PlatformTransactionManager transactionManager) {
        this.events = events;
        this.objectMapper = objectMapper;
        this.tx = new TransactionTemplate(transactionManager);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public WorkflowEvent append(AuditEntry entry) {
        String message = truncate(SecretPatterns.redact(entry.message()), 2000);
        String refs = truncate(SecretPatterns.redact(json(entry.refs())), 2000);
        String details = entry.details().isEmpty() ? null : SecretPatterns.redact(json(entry.details()));
        Object lock = locks.computeIfAbsent(entry.runId(), id -> new Object());
        WorkflowEvent saved;
        synchronized (lock) {
            saved = tx.execute(s -> {
                Optional<WorkflowEvent> last = events.findFirstByRunIdOrderBySequenceDesc(entry.runId());
                long sequence = last.map(e -> e.getSequence() + 1).orElse(1L);
                String prevHash = last.map(WorkflowEvent::getHash).orElse(null);
                Instant at = Instant.now().truncatedTo(ChronoUnit.MILLIS);
                WorkflowEvent e = WorkflowEvent.builder()
                        .runId(entry.runId()).sequence(sequence).taskKey(entry.taskKey()).attempt(entry.attempt())
                        .agentId(entry.agentId()).type(entry.type()).fromState(entry.fromState()).toState(entry.toState())
                        .actor(entry.actor()).executionMode(entry.executionMode()).model(entry.model())
                        .refsJson(refs).message(message).detailsJson(details).prevHash(prevHash).createdAt(at)
                        .build();
                e.setHash(hash(e));
                return events.save(e);
            });
        }
        if (saved == null) {
            throw new IllegalStateException("audit append returned no event");
        }
        log.info("workflow-audit run={} seq={} task={} type={} {}{} actor={} {}", saved.getRunId(), saved.getSequence(),
                saved.getTaskKey(), saved.getType(), saved.getFromState() == null ? "" : saved.getFromState() + "->",
                saved.getToState() == null ? "" : saved.getToState(), saved.getActor(), message == null ? "" : message);
        return saved;
    }

    public List<WorkflowEvent> events(Long runId) {
        return events.findByRunIdOrderBySequenceAsc(runId);
    }

    public Verification verify(Long runId) {
        List<WorkflowEvent> chain = events(runId);
        String prev = null;
        long expected = 1;
        for (WorkflowEvent e : chain) {
            if (e.getSequence() != expected) {
                return new Verification(runId, false, (int) (expected - 1), expected, "sequence gap: expected " + expected + ", found " + e.getSequence());
            }
            if (!java.util.Objects.equals(prev, e.getPrevHash())) {
                return new Verification(runId, false, (int) (expected - 1), e.getSequence(), "prevHash does not match the previous event's hash");
            }
            if (!hash(e).equals(e.getHash())) {
                return new Verification(runId, false, (int) (expected - 1), e.getSequence(), "event content does not match its hash");
            }
            prev = e.getHash();
            expected++;
        }
        return new Verification(runId, true, chain.size(), null, "chain intact");
    }

    /** Canonical, field-ordered serialization of everything except id and hash. */
    static String canonical(WorkflowEvent e) {
        return String.join("\u001f",
                String.valueOf(e.getPrevHash()), String.valueOf(e.getRunId()), String.valueOf(e.getSequence()),
                String.valueOf(e.getType()), String.valueOf(e.getTaskKey()), String.valueOf(e.getAttempt()),
                String.valueOf(e.getAgentId()), String.valueOf(e.getFromState()), String.valueOf(e.getToState()),
                String.valueOf(e.getActor()), String.valueOf(e.getExecutionMode()), String.valueOf(e.getModel()),
                String.valueOf(e.getRefsJson()), String.valueOf(e.getMessage()), String.valueOf(e.getDetailsJson()),
                String.valueOf(e.getCreatedAt() == null ? null : e.getCreatedAt().toEpochMilli()));
    }

    static String hash(WorkflowEvent e) {
        return Hashes.sha256(canonical(e));
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            return String.valueOf(value);
        }
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max - 3) + "...";
    }
}
