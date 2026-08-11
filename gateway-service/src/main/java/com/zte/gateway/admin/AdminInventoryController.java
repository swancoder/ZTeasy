package com.zte.gateway.admin;

import com.zte.gateway.inventory.DuplicateServiceNameException;
import com.zte.gateway.inventory.InventoryEntry;
import com.zte.gateway.inventory.InventoryService;
import com.zte.gateway.inventory.InventoryView;
import com.zte.gateway.inventory.TargetType;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Admin Console API (ADR-016): CRUD for the APIM inventory registry, for the
 * React SPA's "Registry" tab.
 *
 * <p>Security: covered by the same {@code u2s-admin-console-api} YAML rule
 * and {@link AdminAuthorizationFilter} as every other {@code /api/v1/admin/**}
 * controller — that filter's path check is generic, so no new security
 * wiring is needed for this new sub-path.
 */
@RestController
@RequestMapping("/api/v1/admin/inventory")
class AdminInventoryController {

    private final InventoryService inventoryService;

    AdminInventoryController(InventoryService inventoryService) {
        this.inventoryService = inventoryService;
    }

    @GetMapping
    public Mono<List<InventoryView>> list() {
        return inventoryService.list();
    }

    @PostMapping
    public Mono<ResponseEntity<Object>> create(@RequestBody InventoryRequest request) {
        return inventoryService.create(request.name(), request.targetType(), request.baseUrl(), request.managementUrl())
                .<ResponseEntity<Object>>map(entry -> ResponseEntity.status(HttpStatus.CREATED).body(entry))
                .onErrorResume(DuplicateServiceNameException.class, ex -> Mono.just(
                        ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", ex.getMessage()))));
    }

    @PutMapping("/{id}")
    public Mono<InventoryEntry> update(@PathVariable UUID id, @RequestBody InventoryRequest request) {
        return inventoryService.update(id, request.name(), request.targetType(), request.baseUrl(), request.managementUrl());
    }

    @DeleteMapping("/{id}")
    public Mono<ResponseEntity<Void>> delete(@PathVariable UUID id) {
        return inventoryService.delete(id).thenReturn(ResponseEntity.noContent().build());
    }

    /**
     * Onboarding/update form body — {@code name}, {@code target_type}
     * (dropdown), {@code base_url}. {@code managementUrl} (ADR-016
     * amendment) is optional — {@code null}/omitted means "health polling
     * uses {@code base_url}, same as before"; set it only when a target's
     * {@code /actuator/health} lives at a different host:port.
     */
    record InventoryRequest(String name, TargetType targetType, String baseUrl, String managementUrl) {
    }
}
