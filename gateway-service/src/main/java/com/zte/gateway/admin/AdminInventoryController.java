package com.zte.gateway.admin;

import com.zte.gateway.inventory.DuplicateServiceNameException;
import com.zte.gateway.inventory.SchemaFetchException;
import com.zte.gateway.inventory.ServiceNotFoundException;
import com.zte.gateway.inventory.InventoryService;
import com.zte.gateway.inventory.InventoryView;
import com.zte.gateway.inventory.TargetType;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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
        return inventoryService.create(request.name(), request.targetType(), request.baseUrl(), request.docsUrl(),
                        request.managementUrl())
                .<ResponseEntity<Object>>map(entry -> ResponseEntity.status(HttpStatus.CREATED).body(entry))
                .onErrorResume(DuplicateServiceNameException.class, ex -> Mono.just(
                        ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", ex.getMessage()))));
    }

    @PutMapping("/{id}")
    public Mono<ResponseEntity<Object>> update(@PathVariable UUID id, @RequestBody InventoryRequest request) {
        return inventoryService.update(id, request.name(), request.targetType(), request.baseUrl(), request.docsUrl(),
                        request.managementUrl())
                .<ResponseEntity<Object>>map(ResponseEntity::ok)
                .onErrorResume(DuplicateServiceNameException.class, ex -> Mono.just(
                        ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", ex.getMessage()))))
                .onErrorResume(ServiceNotFoundException.class, ex -> Mono.just(
                        ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", ex.getMessage()))));
    }

    @DeleteMapping("/{id}")
    public Mono<ResponseEntity<Void>> delete(@PathVariable UUID id) {
        return inventoryService.delete(id).thenReturn(ResponseEntity.noContent().build());
    }

    /**
     * The raw payload from {@code id}'s last successful discovery probe
     * (ADR-016 amendment) — the OpenAPI document for {@code REST}, the
     * JSON-RPC {@code tools/list} response for {@code MCP}. Returned
     * verbatim as the response body (not re-wrapped/re-quoted as a JSON
     * string field) so the frontend can {@code JSON.parse} it directly.
     * {@code 404} if {@code id} doesn't exist or nothing has been captured
     * yet — both collapse to an empty {@code Mono<String>} in {@link
     * InventoryService#getDiscoveredSchema}.
     */
    @GetMapping("/{id}/schema")
    public Mono<ResponseEntity<String>> schema(@PathVariable UUID id) {
        return inventoryService.getDiscoveredSchema(id)
                .map(schema -> ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(schema))
                .defaultIfEmpty(ResponseEntity.notFound().build());
    }

    /**
     * Synchronous, UI-triggered discovery (ADR-016 amendment) — unlike
     * onboarding's background {@code AutoDiscoveryWorker} trigger, this
     * call doesn't return until the probe (and, on success, the {@code
     * discovered_schema} write) has actually completed, so the Admin
     * Console's "Fetch" button can show a real result. {@code 200} (no
     * body) on success; {@code 404} if {@code id} doesn't exist; {@code
     * 502 Bad Gateway} — not the task's literal "400/500" — if the target
     * was unreachable, timed out, or returned no valid JSON, since that's
     * the semantically correct code for "this gateway couldn't get a valid
     * response from an upstream it proxies to," and this service already
     * is exactly that kind of gateway.
     */
    @PostMapping("/{id}/schema/fetch")
    public Mono<ResponseEntity<Object>> fetchSchema(@PathVariable UUID id) {
        return inventoryService.fetchSchemaNow(id)
                .<ResponseEntity<Object>>thenReturn(ResponseEntity.ok().build())
                .onErrorResume(ServiceNotFoundException.class, ex -> Mono.just(
                        ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", ex.getMessage()))))
                .onErrorResume(SchemaFetchException.class, ex -> Mono.just(
                        ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("error", ex.getMessage()))));
    }

    /**
     * Onboarding/update form body — {@code name}, {@code target_type}
     * (dropdown), {@code base_url}. {@code managementUrl} (ADR-016
     * amendment) is optional — {@code null}/omitted means "health polling
     * uses {@code base_url}, same as before"; set it only when a target's
     * {@code /actuator/health} lives at a different host:port. {@code
     * docsUrl} (ADR-016 amendment) is optional and {@code REST}-only —
     * {@code null}/omitted keeps probing {@code {baseUrl}/v3/api-docs}.
     */
    record InventoryRequest(String name, TargetType targetType, String baseUrl, String docsUrl, String managementUrl) {
    }
}
