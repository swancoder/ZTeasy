package com.zte.gateway.inventory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Seeds {@code service-a}/{@code service-b} into {@code inventory_services}
 * on startup, once, if not already registered (ADR-017).
 *
 * <p>Routing is 100% {@code inventory_services}-driven ({@link
 * InventoryRouteDefinitionLocator}) — the old hardcoded Gateway routes are
 * gone. Without this seeder, a fresh deployment's registry starts empty and
 * {@code /api/v1/service-a/**}/{@code /api/v1/service-b/**} wouldn't route
 * anywhere until an operator manually onboards both via the Admin Console —
 * a real regression from the old "just {@code docker compose up}" zero-config
 * experience. This preserves that experience without reintroducing any
 * hardcoded route: it only ever populates the DB, once, at the same
 * {@code base_url} values {@code GatewayRouteConfig} used to hardcode
 * directly into routing ({@code service-a.uri}/{@code service-b.uri} —
 * unchanged property names, repurposed).
 *
 * <p>Never overwrites an existing row — {@code existsByName} first, skip if
 * present — so an operator who has since edited {@code base_url}, added a
 * {@code management_url}/{@code docs_url}, or renamed the entry never has
 * that work silently reverted by a restart. Doesn't set {@code
 * management_url}: no equivalent bootstrap property exists for it (only
 * {@code service-a.uri}/{@code service-b.uri}, the mTLS API port), so a
 * freshly bootstrapped entry health-polls its own {@code base_url} until an
 * operator sets one — same starting point every manually onboarded {@code
 * REST} entry already has.
 */
@Component
public class InventoryBootstrapSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger("ZTE-INVENTORY");

    private final InventoryService inventoryService;
    private final InventoryRepository repository;
    private final String serviceAUri;
    private final String serviceBUri;

    public InventoryBootstrapSeeder(InventoryService inventoryService, InventoryRepository repository,
                                     @Value("${service-a.uri:}") String serviceAUri,
                                     @Value("${service-b.uri:}") String serviceBUri) {
        this.inventoryService = inventoryService;
        this.repository = repository;
        this.serviceAUri = serviceAUri;
        this.serviceBUri = serviceBUri;
    }

    @Override
    public void run(ApplicationArguments args) {
        seedIfMissing("service-a", serviceAUri);
        seedIfMissing("service-b", serviceBUri);
    }

    private void seedIfMissing(String name, String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return;
        }
        repository.existsByName(name)
                .flatMap(exists -> exists
                        ? Mono.empty()
                        : inventoryService.create(name, TargetType.REST, baseUrl, null, null))
                .subscribe(
                        entry -> log.info("[ZTE-INVENTORY] bootstrap-seeded '{}' at {}", name, baseUrl),
                        ex -> log.warn("[ZTE-INVENTORY] bootstrap seeding of '{}' failed: {}", name, ex.toString()));
    }
}
