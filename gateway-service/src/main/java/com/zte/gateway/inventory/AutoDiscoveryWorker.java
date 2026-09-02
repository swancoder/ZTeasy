package com.zte.gateway.inventory;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeoutException;

/**
 * Fetches a newly (or re-)onboarded service's schema/tool list right after
 * registration (ADR-016) — proof the gateway can actually talk to it, not
 * just that an operator typed a plausible URL. {@code REST} services are
 * probed via {@code GET {base_url}/v3/api-docs} by default, or an explicit
 * {@code docs_url} override (ADR-016 amendment — a full absolute URL, for a
 * target whose OpenAPI document doesn't live at that conventional path);
 * {@code MCP} agents via a stateless {@code POST {base_url}/message}
 * JSON-RPC {@code tools/list} call — mirroring the URL shape this gateway's
 * own MCP proxy uses ({@code McpBackendClient}), on the assumption that
 * schema discovery doesn't need the full {@code GET /sse} session handshake
 * a live tool call does. {@code docs_url} is REST-only — there's no
 * equivalent override for the {@code tools/list} convention.
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
 * This is {@link #discoverAndUpdateStatus} — the passive, scheduled/
 * onboarding-triggered path.
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
 * an HTML error page, for instance), which would otherwise fail the whole
 * update including the {@code status} write it's chained after; a blank
 * body (the historic empty-200 case this worker already tolerated) is
 * treated the same way — {@code ACTIVE}, nothing captured — rather than
 * attempting to cast an empty string, which Postgres rejects outright (not
 * valid JSON). <strong>This means {@code status == ACTIVE} does not
 * reliably imply a schema was captured</strong> — the Admin Console must
 * not use {@code status} alone to decide whether a schema is viewable (see
 * {@link InventoryView#hasSchema()}).
 *
 * <p><strong>Synchronous fetch (ADR-016 amendment):</strong> {@link
 * #fetchSchemaNow}, behind {@code POST .../inventory/{id}/schema/fetch}, is
 * the UI-triggered counterpart — same underlying probe ({@link
 * #fetchBody}), but deliberately <em>stricter</em>: a 2xx with an empty or
 * non-JSON body is a <em>failure</em> here ({@link SchemaFetchException}),
 * not a silent {@code ACTIVE}-with-nothing-captured. An operator who just
 * clicked "Fetch" needs a real yes/no answer, not the background worker's
 * more tolerant "reachable enough to route" semantics.
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
 * component in this module (no special-casing needed here). Applies
 * whether or not {@code .baseUrl(...)} is chained before {@code .build()}
 * — the connector is a property of the shared builder instance, not of any
 * particular {@code .uri(...)} call.
 */
@Component
public class AutoDiscoveryWorker {

    private static final Logger log = LoggerFactory.getLogger("ZTE-INVENTORY-DISCOVERY");

    private final WebClient.Builder webClientBuilder;
    /** ADR-038: an MCP backend authorises the gateway by a hop-specific certificate. */
    private final org.springframework.beans.factory.ObjectProvider<
            org.springframework.http.client.reactive.ReactorClientHttpConnector> mcpConnector;
    private final InventoryRepository repository;
    private final ObjectMapper objectMapper;
    private final Duration timeout;

    public AutoDiscoveryWorker(WebClient.Builder webClientBuilder,
                                @org.springframework.beans.factory.annotation.Qualifier("mcpBackendConnector")
                                org.springframework.beans.factory.ObjectProvider<
                                        org.springframework.http.client.reactive.ReactorClientHttpConnector> mcpConnector,
                                InventoryRepository repository,
                                ObjectMapper objectMapper,
                                @Value("${zte.inventory.discovery-timeout-ms:5000}") long timeoutMs) {
        this.webClientBuilder = webClientBuilder;
        this.mcpConnector = mcpConnector;
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.timeout = Duration.ofMillis(timeoutMs);
    }

    public Mono<Void> discoverAndUpdateStatus(InventoryEntry entry) {
        return fetchBody(entry)
                .map(ProbeResult::active)
                .onErrorResume(ex -> {
                    log.info("[ZTE-INVENTORY-DISCOVERY] id={} name={} unreachable/failed: {}",
                            entry.id(), entry.name(), ex.toString());
                    return Mono.just(ProbeResult.WARNING);
                })
                .doOnNext(result -> log.info("[ZTE-INVENTORY-DISCOVERY] id={} name={} -> {}",
                        entry.id(), entry.name(), result.status()))
                .flatMap(result -> persist(entry.id(), result));
    }

    /** See the class Javadoc's "Synchronous fetch" section. */
    public Mono<Void> fetchSchemaNow(InventoryEntry entry) {
        return fetchBody(entry)
                .flatMap(body -> isValidJson(body)
                        ? persist(entry.id(), ProbeResult.active(body))
                        : Mono.error(new SchemaFetchException(
                                "Target responded, but the body wasn't valid, non-empty JSON")))
                .onErrorMap(ex -> !(ex instanceof SchemaFetchException),
                        ex -> new SchemaFetchException(describeFailure(ex), ex));
    }

    private String describeFailure(Throwable ex) {
        if (ex instanceof TimeoutException) {
            return "Timed out waiting for a response after " + timeout.toMillis() + "ms";
        }
        if (ex instanceof WebClientResponseException responseEx) {
            return "Target returned HTTP " + responseEx.getStatusCode().value();
        }
        return "Could not reach target: " + ex.getMessage();
    }

    private Mono<Void> persist(UUID id, ProbeResult result) {
        Mono<Void> statusUpdate = repository.updateStatus(id, result.status().name());
        return isValidJson(result.schema())
                ? statusUpdate.then(repository.updateDiscoveredSchema(id, result.schema()))
                : statusUpdate;
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

    private Mono<String> fetchBody(InventoryEntry entry) {
        Mono<String> body = entry.targetType() == TargetType.MCP
                ? fetchMcpToolsListBody(entry.baseUrl())
                : fetchRestApiDocsBody(resolveRestDocsUrl(entry));
        return body.timeout(timeout);
    }

    /**
     * {@code docs_url} if set (a full absolute URL — used as-is), else
     * {@code {base_url}/v3/api-docs} with any trailing slash on {@code
     * base_url} stripped first, so the fallback never produces a
     * double-slash path (string concatenation, unlike the old {@code
     * WebClient.Builder#baseUrl(...)}-based construction, doesn't
     * normalize that itself).
     */
    private String resolveRestDocsUrl(InventoryEntry entry) {
        String docsUrl = entry.docsUrl();
        if (docsUrl != null && !docsUrl.isBlank()) {
            return docsUrl;
        }
        String baseUrl = entry.baseUrl();
        String normalizedBaseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        return normalizedBaseUrl + "/v3/api-docs";
    }

    private Mono<String> fetchRestApiDocsBody(String url) {
        return webClientBuilder.build()
                .get()
                .uri(url)
                .retrieve()
                .bodyToMono(String.class)
                .defaultIfEmpty("");
    }

    private Mono<String> fetchMcpToolsListBody(String baseUrl) {
        Map<String, Object> toolsListRequest = Map.of(
                "jsonrpc", "2.0",
                "id", 1,
                "method", "tools/list",
                "params", Map.of());

        // Discovery talks to the same endpoint the proxy does, so it needs the same
        // identity (ADR-038) — otherwise onboarding an MCP backend would fail with a
        // 403 that looks like the backend being down.
        var connector = mcpConnector.getIfAvailable();
        WebClient.Builder builder = connector == null ? webClientBuilder
                : webClientBuilder.clone().clientConnector(connector);
        return builder.baseUrl(baseUrl).build()
                .post()
                .uri("/message")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(toolsListRequest)
                .retrieve()
                .bodyToMono(String.class)
                .defaultIfEmpty("");
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
