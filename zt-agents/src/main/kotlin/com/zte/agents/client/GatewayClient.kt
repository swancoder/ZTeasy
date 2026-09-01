package com.zte.agents.client

import com.zte.agents.client.model.PolicyDto
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Flux

/**
 * Fetches ZTE access policies from the gateway's internal REST endpoint.
 *
 * The endpoint requires no JWT for MVP. Where the gateway is reachable from
 * outside its own network (any real deployment — see ZTeasy ADR-027), it also
 * demands a shared secret: set the same value in both processes'
 * `ZTE_INTERNAL_API_KEY` and it is sent as `X-ZTE-Internal-Key` on every call.
 * Unset (local dev on the Docker bridge) means no header and no check, exactly
 * as before. Production upgrade path: Keycloak client_credentials grant →
 * Bearer token header.
 */
@Component
class GatewayClient(
    gatewayWebClients: GatewayWebClients,
    @Value("\${zte.gateway.internal-uri:http://localhost:8080}") gatewayUri: String,
    @Value("\${zte.internal.api-key:}") internalApiKey: String
) {
    private val log = LoggerFactory.getLogger(GatewayClient::class.java)

    private val webClient = gatewayWebClients.builder()
        .baseUrl(gatewayUri)
        .apply { builder ->
            internalApiKey.trim().takeIf { it.isNotEmpty() }
                ?.let { builder.defaultHeader("X-ZTE-Internal-Key", it) }
        }
        .build()

    fun fetchPolicies(): Flux<PolicyDto> {
        log.debug("Fetching policies from gateway at {}", webClient)
        return webClient.get()
            .uri("/api/v1/internal/policies")
            .retrieve()
            .bodyToFlux(PolicyDto::class.java)
            .doOnNext { log.debug("Received policy: {}", it) }
    }
}
