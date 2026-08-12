package com.zte.gateway.inventory;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.event.RefreshRoutesEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodic safety-net for {@link InventoryRouteDefinitionLocator}'s
 * freshness (ADR-017). {@link InventoryService} publishes {@link
 * RefreshRoutesEvent} immediately after {@code create}/{@code update}/
 * {@code delete}, but {@code AutoDiscoveryWorker}/{@code HealthPollingService}
 * write {@code status} directly via {@code InventoryRepository} — not
 * through {@code InventoryService} — so a service transitioning {@code
 * ACTIVE}↔{@code DOWN} (or {@code PENDING}→{@code ACTIVE}/{@code WARNING}
 * after its first discovery probe) needs this periodic catch-all instead.
 * Same order-of-magnitude interval as {@code HealthPollingService}'s own
 * poll — routing doesn't need tighter freshness than the health signal
 * driving it does.
 */
@Component
public class InventoryRouteRefreshScheduler {

    private final ApplicationEventPublisher eventPublisher;

    public InventoryRouteRefreshScheduler(ApplicationEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    @Scheduled(fixedDelayString = "${zte.routing.refresh-interval-ms:30000}")
    public void refresh() {
        eventPublisher.publishEvent(new RefreshRoutesEvent(this));
    }
}
