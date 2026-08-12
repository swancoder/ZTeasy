package com.zte.gateway.inventory;

import org.springframework.cloud.gateway.filter.FilterDefinition;
import org.springframework.cloud.gateway.handler.predicate.PredicateDefinition;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.cloud.gateway.route.RouteDefinitionLocator;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.net.URI;
import java.util.List;

/**
 * Dynamic, DB-backed replacement for {@code GatewayRouteConfig}'s hardcoded
 * {@code service-a}/{@code service-b} routes (ADR-017) — one {@code Path}
 * route per {@code REST}, {@code ACTIVE}/{@code WARNING} row in {@code
 * inventory_services}, mapping {@code /api/v1/{name}/**} to that row's
 * {@code base_url}. Matches {@link com.zte.gateway.policy.def.RequestTargetResolver}'s
 * own {@code /api/v1/{name}/...} → {@code name} convention exactly, so
 * policy enforcement and routing always agree on what "the target service"
 * is for a given path.
 *
 * <p>{@code MCP} targets are deliberately excluded — they're served by
 * {@code McpProxyHandler}'s own {@code GET /sse}/{@code POST /message}
 * WebFlux handlers, a separate mechanism this locator doesn't participate
 * in (ADR-009). {@code PENDING} (never discovered) and {@code DOWN} (failing
 * health checks) rows are excluded too — {@code WARNING} is deliberately
 * included, matching {@link InventoryStatus}'s own "reachable enough to
 * route" semantics.
 *
 * <p><strong>Freshness:</strong> Spring Cloud Gateway's default {@code
 * CachingRouteLocator} only re-calls {@link #getRouteDefinitions()} on a
 * {@code RefreshRoutesEvent} — confirmed by inspecting {@code
 * GatewayAutoConfiguration}/{@code CachingRouteLocator}'s bytecode, not
 * assumed. {@link InventoryRouteRefreshScheduler} publishes that event
 * periodically (the safety net covering {@code AutoDiscoveryWorker}/{@code
 * HealthPollingService}'s direct {@code InventoryRepository} status writes,
 * which don't go through {@link InventoryService}) and {@link
 * InventoryService} publishes it immediately after {@code create}/{@code
 * update}/{@code delete}, so a freshly onboarded service routes without
 * waiting for the next scheduled refresh.
 */
@Component
public class InventoryRouteDefinitionLocator implements RouteDefinitionLocator {

    private final InventoryRepository repository;

    public InventoryRouteDefinitionLocator(InventoryRepository repository) {
        this.repository = repository;
    }

    @Override
    public Flux<RouteDefinition> getRouteDefinitions() {
        return repository.findAll()
                .filter(entry -> entry.targetType() == TargetType.REST)
                .filter(entry -> entry.status() == InventoryStatus.ACTIVE || entry.status() == InventoryStatus.WARNING)
                .map(InventoryRouteDefinitionLocator::toRouteDefinition);
    }

    private static RouteDefinition toRouteDefinition(InventoryEntry entry) {
        RouteDefinition route = new RouteDefinition();
        route.setId("inventory-" + entry.name());
        route.setUri(URI.create(entry.baseUrl()));

        PredicateDefinition pathPredicate = new PredicateDefinition();
        pathPredicate.setName("Path");
        pathPredicate.addArg("patterns", "/api/v1/" + entry.name() + "/**");
        route.setPredicates(List.of(pathPredicate));

        FilterDefinition headerFilter = new FilterDefinition();
        headerFilter.setName("AddRequestHeader");
        headerFilter.addArg("name", "X-Gateway-Source");
        headerFilter.addArg("value", "zte-gateway");
        route.setFilters(List.of(headerFilter));

        return route;
    }
}
