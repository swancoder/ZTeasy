package com.zte.gateway.mcp.approval;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

/**
 * Nudges an approval that is running out of time (Stage 36, ADR-036).
 *
 * <p>ADR-034 gave held calls a deadline and ADR-035 announced them once, when
 * raised. Between those two an item can be announced at 18:00 and expire at 18:00
 * the next day having never been seen again — the deadline made silence a failure
 * mode with a timer on it.
 *
 * <p>Thresholds are fractions of each item's own lifetime rather than a fixed lead
 * time, because the lifetime is configurable: "an hour before expiry" is nonsense
 * for a one-minute TTL, while "halfway" holds at any scale.
 */
@Component
class ApprovalReminderSweeper {

    private static final Logger log = LoggerFactory.getLogger(ApprovalReminderSweeper.class);

    private final PendingApprovalService service;
    private final ApprovalNotifier notifier;
    private final List<String> stages;

    ApprovalReminderSweeper(PendingApprovalService service, ApprovalNotifier notifier,
                             @Value("${zte.approvals.reminder-fractions:0.5}") String fractions) {
        this.service = service;
        this.notifier = notifier;
        this.stages = Arrays.stream(fractions.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    @Scheduled(fixedDelayString = "${zte.approvals.reminder-interval-ms:60000}",
               initialDelayString = "${zte.approvals.reminder-interval-ms:60000}")
    void sweep() {
        if (stages.isEmpty()) {
            return;
        }
        Instant now = Instant.now();
        service.listPending()
                .flatMap(approval -> Flux.fromIterable(due(approval, now))
                        .concatMap(stage -> notifier.remind(approval, stage, secondsRemaining(approval, now))))
                .doOnNext(sent -> log.info("[ZTE-APPROVAL] reminder {} for approvalId={} -> {}",
                        sent.stage(), sent.approvalId(), sent.status()))
                .onErrorContinue((e, o) -> log.warn("[ZTE-APPROVAL] reminder sweep failed for {}: {}", o, e.toString()))
                .subscribe();
    }

    /**
     * Which thresholds this item has passed. Whether each was already sent is not
     * checked here — that decision belongs to the claim row's unique index, which
     * is the only check that also holds against the other gateway instance
     * (ADR-036). Asking the database "was it sent?" and then sending is exactly the
     * race this design avoids.
     */
    List<String> due(PendingApproval approval, Instant now) {
        if (approval.expiresAt() == null || approval.isExpired(now)) {
            return List.of();
        }
        Duration lifetime = Duration.between(approval.requestedAt(), approval.expiresAt());
        if (lifetime.isZero() || lifetime.isNegative()) {
            return List.of();
        }
        double elapsed = (double) Duration.between(approval.requestedAt(), now).toMillis() / lifetime.toMillis();
        return stages.stream().filter(stage -> {
            try {
                return elapsed >= Double.parseDouble(stage);
            } catch (NumberFormatException e) {
                // A misconfigured fraction must not silently disable reminders for
                // everything else in the list.
                log.warn("[ZTE-APPROVAL] ignoring unparseable reminder fraction '{}'", stage);
                return false;
            }
        }).toList();
    }

    private long secondsRemaining(PendingApproval approval, Instant now) {
        return Math.max(0, approval.expiresAt().getEpochSecond() - now.getEpochSecond());
    }
}
