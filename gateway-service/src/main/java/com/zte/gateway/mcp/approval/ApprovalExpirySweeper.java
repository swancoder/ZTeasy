package com.zte.gateway.mcp.approval;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Moves undecided approvals to EXPIRED once their deadline passes (Stage 34, ADR-034).
 *
 * <p>Runs on a timer, so an item can sit past its deadline for up to one interval
 * before the row changes — which is why {@link PendingApprovalService} also checks
 * the deadline when someone actually decides. The timer keeps the queue honest for
 * anyone reading it; the check keeps a late decision from executing. Neither alone
 * is enough.
 *
 * <p>Both gateway apps run this (ADR-028 puts the same image behind two front doors).
 * Two sweepers racing on the same row is harmless: the second finds a status that is
 * no longer PENDING and its query simply returns nothing.
 */
@Component
class ApprovalExpirySweeper {

    private static final Logger log = LoggerFactory.getLogger(ApprovalExpirySweeper.class);

    private final PendingApprovalService service;

    ApprovalExpirySweeper(PendingApprovalService service) {
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${zte.approvals.sweep-interval-ms:60000}",
               initialDelayString = "${zte.approvals.sweep-interval-ms:60000}")
    void sweep() {
        service.findExpired(Instant.now())
                .flatMap(service::expire)
                .doOnNext(a -> log.info("[ZTE-APPROVAL] expired approvalId={} tool={} (deadline {})",
                        a.id(), a.toolName(), a.expiresAt()))
                .onErrorContinue((e, o) -> log.warn("[ZTE-APPROVAL] expiry sweep failed for {}: {}", o, e.toString()))
                .subscribe();
    }
}
