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
 * The endpoint is network-restricted (Docker bridge only) and requires no JWT for MVP.
 * Production upgrade path: Keycloak client_credentials grant → Bearer token header.
 */
@Component
class GatewayClient(
    @Value("\${zte.gateway.internal-uri:http://localhost:8080}") gatewayUri: String
) {
    private val log = LoggerFactory.getLogger(GatewayClient::class.java)

    private val webClient = WebClient.builder()
        .baseUrl(gatewayUri)
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
