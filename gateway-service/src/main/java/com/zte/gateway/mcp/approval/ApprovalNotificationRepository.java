package com.zte.gateway.mcp.approval;

import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;

import java.util.Collection;
import java.util.UUID;

/** Reactive repository for {@link ApprovalNotification} (Stage 35, ADR-035). */
public interface ApprovalNotificationRepository extends ReactiveCrudRepository<ApprovalNotification, UUID> {

    /** Deliveries for a page of approvals — one query for the whole queue, not one per row. */
    Flux<ApprovalNotification> findByApprovalIdInOrderByCreatedAtDesc(Collection<UUID> approvalIds);
}
