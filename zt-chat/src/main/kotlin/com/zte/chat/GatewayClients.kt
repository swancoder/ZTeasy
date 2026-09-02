package com.zte.chat

import io.netty.handler.ssl.SslContextBuilder
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.web.reactive.function.client.WebClient
import reactor.netty.http.client.HttpClient
import java.io.File
import java.io.FileInputStream
import java.security.KeyStore
import java.time.Duration
import javax.net.ssl.KeyManagerFactory

/**
 * The one way out of this service: a WebClient that speaks mTLS to the ZTeasy
 * gateway (ADR-039).
 *
 * <p>Two credentials are in play and they answer different questions. The client
 * certificate says *what* is calling — a component the perimeter admitted, which
 * is what {@code MtlsEnforcementWebFilter} requires on the MCP paths. The user's
 * bearer token, relayed per request, says *who* the call is for, and that is what
 * the policy engine decides about (ADR-039's caller model). This service has no
 * identity of its own that can call a tool: without a user's token it can do
 * nothing but fail.
 */
@Configuration
class GatewayClients(
    @Value("\${zte.gateway.uri:https://localhost:8080}") private val gatewayUri: String,
    @Value("\${zte.mtls.certs-dir:./certs}") private val certsDir: String,
    @Value("\${zte.mtls.key-password:}") private val keyPassword: String,
    @Value("\${zte.gateway.timeout-seconds:180}") private val timeoutSeconds: Long
) {
    private val log = LoggerFactory.getLogger(GatewayClients::class.java)

    @Bean
    fun gatewayWebClient(): WebClient {
        val http = HttpClient.create()
            .responseTimeout(Duration.ofSeconds(timeoutSeconds))
            .secure { spec -> spec.sslContext(sslContext()) }
        return WebClient.builder()
            .baseUrl(gatewayUri)
            .clientConnector(ReactorClientHttpConnector(http))
            .build()
    }

    /**
     * Trusts the public CAs *and* ours (ADR-040).
     *
     * <p>The gateway used to be reached at `https://gateway:8080` with a certificate
     * from our own dev CA — so trusting that CA alone was enough. Now there is one
     * front door on a real domain, and its certificate comes from a public issuer via
     * Azure. Our CA is still needed for the rest of the perimeter, so the trust store
     * is the JDK's defaults plus ours rather than one or the other.
     */
    private fun sslContext() = SslContextBuilder.forClient()
        .keyManager(keyManagerFactory())
        .trustManager(trustManagerFactory())
        .build()

    private fun trustManagerFactory(): javax.net.ssl.TrustManagerFactory {
        val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }

        // The JDK's own trust anchors, so a publicly-issued certificate validates.
        val defaults = javax.net.ssl.TrustManagerFactory
            .getInstance(javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm())
            .apply { init(null as KeyStore?) }
        defaults.trustManagers.filterIsInstance<javax.net.ssl.X509TrustManager>()
            .flatMap { it.acceptedIssuers.asList() }
            .forEachIndexed { i, cert -> store.setCertificateEntry("system-$i", cert) }

        // Plus the perimeter's own CA.
        File("$certsDir/ca.crt").inputStream().use { input ->
            val ca = java.security.cert.CertificateFactory.getInstance("X.509").generateCertificate(input)
            store.setCertificateEntry("zte-ca", ca)
        }

        return javax.net.ssl.TrustManagerFactory
            .getInstance(javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm())
            .apply { init(store) }
    }

    private fun keyManagerFactory(): KeyManagerFactory {
        // client.p12 is the shared perimeter identity (ADR-004). This service is
        // deliberately NOT given the gateway's ADR-038 hop certificate: it must be
        // able to reach the gate and unable to reach past it.
        val password = keyPassword.toCharArray()
        val keyStore = KeyStore.getInstance("PKCS12")
        FileInputStream("$certsDir/client.p12").use { keyStore.load(it, password) }
        log.info("[ZTE-CHAT] outbound identity loaded from {}/client.p12", certsDir)
        return KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            .apply { init(keyStore, password) }
    }
}
