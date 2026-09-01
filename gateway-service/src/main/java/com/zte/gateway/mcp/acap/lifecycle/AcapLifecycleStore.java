package com.zte.gateway.mcp.acap.lifecycle;

import com.zte.gateway.mcp.acap.AcapProfile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory mirror of the ACAP lifecycle overlay (Stage 32, ADR-032) — the
 * same shape as {@code PolicyActivationStore}: loaded once at startup,
 * updated on every change, read with a plain map lookup during evaluation
 * (which is zero-I/O by design, ADR-009).
 */
@Component
public class AcapLifecycleStore {

    private static final Logger log = LoggerFactory.getLogger(AcapLifecycleStore.class);

    private final AcapLifecycleRepository repository;
    private final AcapReauthorizationRepository reauthRepository;
    private final Map<String, AcapLifecycleState> byAgentId = new ConcurrentHashMap<>();

    public AcapLifecycleStore(AcapLifecycleRepository repository, AcapReauthorizationRepository reauthRepository) {
        this.repository = repository;
        this.reauthRepository = reauthRepository;
    }

    /**
     * Loaded on application-ready, NOT in the constructor: bean construction
     * races Flyway, and a constructor read hit "relation does not exist" on
     * the very run that created the table (observed live). Everything before
     * this point simply sees the ACTIVE default, which is the correct
     * fallback anyway.
     */

    /**
     * Periodic refresh (Stage 32, ADR-032). The in-memory mirror is what
     * keeps evaluation zero-I/O, but it also means an instance only learns
     * about a change it made itself: found live on the two-front-door cloud
     * topology (ADR-028), where suspending an agent through the browser-
     * facing app left the agent-facing one still allowing its calls. Polling
     * bounds that divergence to one interval instead of "until restart".
     * A push/invalidation channel would be better and is noted in the ADR.
     */
    @Scheduled(fixedDelayString = "${zte.acap.lifecycle-refresh-ms:20000}",
               initialDelayString = "${zte.acap.lifecycle-refresh-ms:20000}")
    void refresh() {
        load();
    }

    @EventListener(ApplicationReadyEvent.class)
    void load() {
        repository.findAll()
                .doOnNext(s -> byAgentId.put(s.agentId(), s))
                .count()
                .subscribe(
                        n -> log.info("[ZTE-ACAP-LIFECYCLE] loaded {} lifecycle state(s)", n),
                        e -> log.warn("[ZTE-ACAP-LIFECYCLE] could not load lifecycle states: {}", e.toString()));
    }

    public String status(String agentId) {
        AcapLifecycleState state = byAgentId.get(agentId);
        return state == null ? AcapLifecycleState.ACTIVE : state.status();
    }

    public boolean isOperational(String agentId) {
        String status = status(agentId);
        return AcapLifecycleState.ACTIVE.equals(status);
    }

    /**
     * The date that actually governs overdue-ness: the DB override when the
     * cycle has been managed in-app, else the profile file's own value.
     * Unparseable file dates are treated as "no due date" (never overdue) —
     * same posture the display badge always had.
     */
    public Optional<LocalDate> effectiveReauthDue(AcapProfile profile) {
        AcapLifecycleState state = byAgentId.get(profile.agentId());
        if (state != null && state.reauthDue() != null) {
            return Optional.of(state.reauthDue());
        }
        String fileDue = profile.agent() == null ? null : profile.agent().reauthDue();
        if (fileDue == null || fileDue.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(LocalDate.parse(fileDue));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    public boolean isReauthOverdue(AcapProfile profile) {
        return isOperational(profile.agentId())
                && effectiveReauthDue(profile).map(due -> due.isBefore(LocalDate.now())).orElse(false);
    }

    public Mono<AcapLifecycleState> setStatus(String agentId, String status, String updatedBy) {
        return repository.upsert(agentId, status, null, updatedBy)
                .then(repository.findById(agentId))
                .doOnNext(saved -> {
                    byAgentId.put(agentId, saved);
                    log.info("[ZTE-ACAP-LIFECYCLE] agent '{}' -> {} by {}", agentId, status, updatedBy);
                });
    }

    /** Records the decision in the append-only history AND moves the due date. */
    public Mono<AcapLifecycleState> reauthorize(String agentId, LocalDate nextDue, String note, String decidedBy) {
        String currentStatus = status(agentId);
        return reauthRepository.save(new AcapReauthorization(null, agentId, decidedBy, Instant.now(), nextDue, note))
                .then(repository.upsert(agentId, currentStatus, nextDue, decidedBy))
                .then(repository.findById(agentId))
                .doOnNext(saved -> {
                    byAgentId.put(agentId, saved);
                    log.info("[ZTE-ACAP-LIFECYCLE] agent '{}' re-authorized by {} until {}", agentId, decidedBy, nextDue);
                });
    }

    public Mono<List<AcapReauthorization>> history(String agentId) {
        return reauthRepository.findByAgentIdOrderByReauthorizedAtDesc(agentId).collectList();
    }
}
