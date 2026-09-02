package com.zte.gateway.config;

import com.zte.auth.mtls.ReloadableSslContextFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.scheduling.annotation.Scheduled;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;

/**
 * Configures the Netty {@link HttpClient} used by Spring Cloud Gateway for all
 * outbound routing calls with mutual TLS (mTLS).
 *
 * <p>Spring Cloud Gateway auto-detects a custom {@link HttpClient} bean and uses it
 * for the {@code NettyRoutingFilter}. By declaring it here, we replace the default
 * non-TLS client with one that presents {@code client.p12} as a client certificate.
 *
 * <p><strong>Hot-reload:</strong> The {@code .secure(spec -> spec.sslContext(...))}
 * lambda passed to Reactor Netty is evaluated <em>per TCP connection</em>. Reading
 * {@code factory.current()} inside the lambda ensures that after a
 * {@link ReloadableSslContextFactory#refresh()} call, every new outbound connection
 * picks up the refreshed {@link io.netty.handler.ssl.SslContext} transparently.
 *
 * <p><strong>No circular dependency:</strong> {@code sslContextFactory} is set as an
 * instance field inside the {@code @Bean} factory method (before the scheduler starts),
 * avoiding the {@code @Autowired}-self-reference cycle.
 *
 * <p><strong>{@code @EnableScheduling} moved to {@code GatewayApplication}
 * (ADR-017)</strong> — it used to live on this class, but this class is
 * {@code @ConditionalOnProperty}-gated and the {@code it} test profile sets
 * {@code zte.mtls.enabled=false}, which meant {@code @EnableScheduling} never
 * activated in any integration test at all. See {@code GatewayApplication}'s
 * Javadoc for the full explanation.
 */
@Configuration
@ConditionalOnProperty(name = "zte.mtls.enabled", havingValue = "true", matchIfMissing = true)
public class MtlsHttpClientConfig {

    private static final Logger log = LoggerFactory.getLogger(MtlsHttpClientConfig.class);

    @Value("${zte.mtls.certs-dir:./certs}")
    private String certsDir;

    @Value("${zte.mtls.key-password}")
    private String keyPassword;

    // Set by the @Bean method below; referenced by the @Scheduled method.
    // No @Autowired — this field is populated by Spring calling sslContextFactory() bean method.
    private ReloadableSslContextFactory sslContextFactory;

    @Bean
    public ReloadableSslContextFactory sslContextFactory() {
        this.sslContextFactory = new ReloadableSslContextFactory(
                certsDir + "/client.p12",
                certsDir + "/truststore.p12",
                keyPassword
        );
        return this.sslContextFactory;
    }

    /**
     * The primary {@link HttpClient} bean used by Spring Cloud Gateway's routing filter.
     *
     * <p>Configured with:
     * <ul>
     *   <li>mTLS: client certificate ({@code client.p12}) + CA truststore</li>
     *   <li>30s response timeout</li>
     * </ul>
     */
    @Bean
    public HttpClient httpClient(ReloadableSslContextFactory factory) {
        return HttpClient.create()
                .responseTimeout(Duration.ofSeconds(30))
                .secure(spec -> spec.sslContext(factory.current()));
    }

    /**
     * @Primary since ADR-038: a second {@link ReactorClientHttpConnector} now exists
     * for the MCP hop, and Spring Boot's {@code ClientHttpConnectorAutoConfiguration}
     * injects this type by parameter with no qualifier — with two candidates and no
     * primary it fails the whole context at startup, which is exactly what happened
     * on the live deployment the first time this shipped. This one stays the default
     * for every outbound call; the MCP hop asks for its own by name.
     */
    @Bean
    @Primary
    public ReactorClientHttpConnector reactorClientHttpConnector(HttpClient httpClient) {
        return new ReactorClientHttpConnector(httpClient);
    }

    /**
     * Periodically checks whether the client keystore file has been replaced
     * (e.g. after cert rotation) and hot-swaps the {@link io.netty.handler.ssl.SslContext}
     * if the modification time has changed.
     */
    @Scheduled(fixedDelayString = "${zte.mtls.refresh-interval-ms:300000}")
    public void refreshSslContext() {
        log.debug("Scheduled mTLS SslContext refresh check");
        if (sslContextFactory != null) sslContextFactory.refresh();
    }
}
