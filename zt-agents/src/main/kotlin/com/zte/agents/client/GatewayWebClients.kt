package com.zte.agents.client

import io.netty.handler.ssl.SslContextBuilder
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import reactor.netty.http.client.HttpClient
import java.io.File

/**
 * Builds WebClients for talking TO the gateway (Stage 31, ADR-031 — closes
 * the FEAT-14 "zt-agents doesn't trust the gateway's dev CA" gap).
 *
 * When `zte.gateway.ca-cert` points at the ZTE-CA's PEM, outbound clients
 * trust exactly that CA — nothing else changes, and the Anthropic client is
 * untouched (it must keep the JVM's default public-CA trust). Unset, clients
 * are built plain, which is correct for an http:// gateway URI and preserves
 * the old behaviour everywhere else.
 */
@Component
class GatewayWebClients(
    @Value("\${zte.gateway.ca-cert:}") private val caCertPath: String
) {
    private val log = LoggerFactory.getLogger(GatewayWebClients::class.java)

    fun builder(): WebClient.Builder {
        val path = caCertPath.trim()
        if (path.isEmpty()) {
            return WebClient.builder()
        }
        val pem = File(path)
        if (!pem.isFile) {
            log.warn("zte.gateway.ca-cert points at '{}' which does not exist — building an untrusting client", path)
            return WebClient.builder()
        }
        val sslContext = SslContextBuilder.forClient().trustManager(pem).build()
        log.info("Gateway clients will trust the CA at {}", path)
        return WebClient.builder()
            .clientConnector(ReactorClientHttpConnector(HttpClient.create().secure { it.sslContext(sslContext) }))
    }
}
