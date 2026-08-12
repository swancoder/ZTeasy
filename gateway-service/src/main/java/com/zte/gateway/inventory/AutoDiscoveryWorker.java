package com.zte.gateway.inventory;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Map;

/**
 * Fetches a newly (or re-)onboarded service's schema/tool list right after
 * registration (ADR-016) — proof the gateway can actually talk to it, not
 * just that an operator typed a plausible URL. {@code REST} services are
 * probed via {@code GET {base_url}/v3/api-docs} (OpenAPI); {@code MCP}
 * agents via a stateless {@code POST {base_url}/message} JSON-RPC {@code
 * tools/list} call — mirroring the URL shape this gateway's own MCP proxy
 * uses ({@code McpBackendClient}), on the assumption that schema discovery
 * doesn't need the full {@code GET /sse} session handshake a live tool call
 * does.
 *
 * <p>Unlike {@code KeycloakIdpAdapter}/{@code McpBackendClient} (one fixed
 * target, configured once), this worker builds a fresh {@link WebClient} per
 * call — {@code base_url} is different for every inventory entry, supplied
 * at onboarding time, not at application startup.
 *
 * <p>Success (2xx) → {@link InventoryStatus#ACTIVE}. Any failure — timeout,
 * connection refused, non-2xx — → {@link InventoryStatus#WARNING}, never a
 * thrown exception back to the caller: a service that can't be reached yet
 * is a normal, expected onboarding outcome (the task's own "degraded state
 * where manual routing is required" framing), not an application error.
 *
 * <p><strong>Captured schema (ADR-016 amendment):</strong> on a successful
 * probe, the raw response body — the OpenAPI document for {@code REST}, the
 * JSON-RPC {@code tools/list} response for {@code MCP} — is persisted to
 * {@code inventory_services.discovered_schema} via {@link
 * InventoryRepository#updateDiscoveredSchema}, for the Admin Console's
 * on-demand schema viewer. Only ever written on success; a failed/{@code
 * WARNING} probe leaves whatever was captured last time untouched, same
 * reasoning as {@code status} itself. Validated with {@link ObjectMapper}
 * before writing — Postgres would reject a plain {@code CAST(text AS
 * jsonb)} of anything that isn't valid JSON (a target returning a 200 with
 * an HTML error page at {@code /v3/api-docs}, for instance), which would
 * otherwise fail the whole update including the {@code status} write it's
 * chained after; a blank body (the historic empty-200 case this worker
 * already tolerated) is treated the same way — {@code ACTIVE}, nothing
 * captured — rather than attempting to cast an empty string, which
 * Postgres rejects outright (not valid JSON).
 *
 * <p><strong>mTLS:</strong> {@code webClientBuilder} below is this
 * application's single Spring-Boot-autoconfigured default {@code
 * WebClient.Builder} — <em>not</em> a plain, unauthenticated client. When
 * {@code zte.mtls.enabled=true} (the production/dev default),
 * {@code MtlsHttpClientConfig} registers the gateway's one {@code
 * ReactorClientHttpConnector} bean, and Spring Boot's own {@code
 * ClientHttpConnectorAutoConfiguration} automatically applies any such
 * singleton connector bean to every autoconfigured {@code WebClient.Builder}
 * in the application context via a {@code WebClientCustomizer} — this class
 * never had to ask for it explicitly, and doesn't need to. Verified two
 * ways, not just inferred from framework docs: (1) bytecode inspection of
 * {@code ClientHttpConnectorAutoConfiguration.webClientHttpConnectorCustomizer(...)}
 * confirms the customizer calls {@code builder.clientConnector(...)}; (2)
 * live, against the real running gateway — {@code curl} with no client
 * certificate against {@code service-a}'s {@code client-auth: need} listener
 * fails at the TLS handshake (no HTTP response at all), while this worker's
 * probe against the identical URL received a real HTTP-level response,
 * which is only possible if a valid client certificate was already
 * presented. When {@code zte.mtls.enabled=false} (the {@code it} test
 * profile — no certs needed in CI), no connector bean exists, the
 * customizer never activates, and this same builder transparently falls
 * back to a plain connector — exactly matching every other outbound
 * component in this module (no special-casing needed here).
 */
@Component
public class AutoDiscoveryWorker {

    private static final Logger log = LoggerFactory.getLogger("ZTE-INVENTORY-DISCOVERY");

    private final WebClient.Builder webClientBuilder;
    private final InventoryRepository repository;
    private final ObjectMapper objectMapper;
    private final Duration timeout;

    public AutoDiscoveryWorker(WebClient.Builder webClientBuilder, InventoryRepository repository,
                                ObjectMapper objectMapper,
                                @Value("${zte.inventory.discovery-timeout-ms:5000}") long timeoutMs) {
        this.webClientBuilder = webClientBuilder;
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.timeout = Duration.ofMillis(timeoutMs);
    }

    public Mono<Void> discoverAndUpdateStatus(InventoryEntry entry) {
        Mono<ProbeResult> probe = entry.targetType() == TargetType.MCP
                ? probeMcpToolsList(entry.baseUrl())
                : probeRestApiDocs(entry.baseUrl());

        return probe
                .timeout(timeout)
                .onErrorResume(ex -> {
                    log.info("[ZTE-INVENTORY-DISCOVERY] id={} name={} baseUrl={} unreachable/failed: {}",
                            entry.id(), entry.name(), entry.baseUrl(), ex.toString());
                    return Mono.just(ProbeResult.WARNING);
                })
                .doOnNext(result -> log.info("[ZTE-INVENTORY-DISCOVERY] id={} name={} -> {}",
                        entry.id(), entry.name(), result.status()))
                .flatMap(result -> {
                    Mono<Void> statusUpdate = repository.updateStatus(entry.id(), result.status().name());
                    return isValidJson(result.schema())
                            ? statusUpdate.then(repository.updateDiscoveredSchema(entry.id(), result.schema()))
                            : statusUpdate;
                });
    }

    private boolean isValidJson(String schema) {
        if (schema == null || schema.isBlank()) {
            return false;
        }
        try {
            objectMapper.readTree(schema);
            return true;
        } catch (Exception ex) {
            log.info("[ZTE-INVENTORY-DISCOVERY] captured body wasn't valid JSON, not persisting: {}", ex.toString());
            return false;
        }
    }

    private Mono<ProbeResult> probeRestApiDocs(String baseUrl) {
        return webClientBuilder.baseUrl(baseUrl).build()
                .get()
                .uri("/v3/api-docs")
                .retrieve()
                .bodyToMono(String.class)
                .defaultIfEmpty("")
                .map(ProbeResult::active);
    }

    private Mono<ProbeResult> probeMcpToolsList(String baseUrl) {
        Map<String, Object> toolsListRequest = Map.of(
                "jsonrpc", "2.0",
                "id", 1,
                "method", "tools/list",
                "params", Map.of());

        return webClientBuilder.baseUrl(baseUrl).build()
                .post()
                .uri("/message")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(toolsListRequest)
                .retrieve()
                .bodyToMono(String.class)
                .defaultIfEmpty("")
                .map(ProbeResult::active);
    }

    /**
     * A probe outcome — {@code status} always set (drives {@code
     * discoverAndUpdateStatus}'s {@code repository.updateStatus} call
     * regardless), {@code schema} the raw captured body, possibly blank
     * (an empty 2xx response body, tolerated the same way this worker
     * always has) or {@code null} ({@link #WARNING} — no probe response to
     * capture at all).
     */
    private record ProbeResult(InventoryStatus status, String schema) {
        private static final ProbeResult WARNING = new ProbeResult(InventoryStatus.WARNING, null);

        private static ProbeResult active(String schema) {
            return new ProbeResult(InventoryStatus.ACTIVE, schema);
        }
    }
}
