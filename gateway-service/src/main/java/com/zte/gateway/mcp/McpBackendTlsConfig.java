package com.zte.gateway.mcp;

import com.zte.auth.mtls.ReloadableSslContextFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;

/**
 * The client identity the gateway uses for one hop only: gateway → MCP backend
 * (Stage 38, ADR-038).
 *
 * <p>Every other outbound mTLS call in this system presents {@code client.p12},
 * whose subject is {@code CN=zte-internal-client} — and ADR-004 already records
 * that this one certificate is shared by everything inside the perimeter. In the
 * cloud deployment that includes the agent runner, which mounts the same certs
 * share. So a backend that accepted "any certificate signed by our CA" would
 * accept a call from the very agents the gateway exists to govern, made directly,
 * with no policy evaluation, no ACAP scope check and no audit row.
 *
 * <p>This connector therefore presents {@code gateway-mcp-client.p12}
 * ({@code CN=zte-gateway-mcp}), a keystore nothing else is given, and the bridge
 * authorises that subject specifically. The certificate is not a secret shared
 * with the backend — it is an identity the backend can verify against the CA.
 */
@Configuration
@ConditionalOnProperty(name = "zte.mtls.enabled", havingValue = "true", matchIfMissing = true)
public class McpBackendTlsConfig {

    private static final Logger log = LoggerFactory.getLogger(McpBackendTlsConfig.class);

    @Bean("mcpBackendConnector")
    public ReactorClientHttpConnector mcpBackendConnector(
            @Value("${zte.mtls.certs-dir:./certs}") String certsDir,
            @Value("${zte.mtls.key-password}") String keyPassword,
            @Value("${mcp-backend.response-timeout-seconds:30}") long timeoutSeconds) {

        ReloadableSslContextFactory factory = new ReloadableSslContextFactory(
                certsDir + "/gateway-mcp-client.p12",
                certsDir + "/truststore.p12",
                keyPassword);
        log.info("[ZTE-MCP] backend hop will present {}/gateway-mcp-client.p12 (ADR-038)", certsDir);

        return new ReactorClientHttpConnector(HttpClient.create()
                .responseTimeout(Duration.ofSeconds(timeoutSeconds))
                .secure(spec -> spec.sslContext(factory.current())));
    }
}
