package com.zte.servicea.config;

import com.zte.auth.mtls.ReloadableSslContextFactory;
import com.zte.auth.obo.UserContextTokenService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;

/**
 * Configures the mTLS {@link WebClient} used by service-a to call service-b,
 * and the {@link UserContextTokenService} for validating incoming OBO tokens.
 *
 * <p>The WebClient uses the same {@code client.p12} keystore as the gateway —
 * service-b trusts any cert signed by the ZTE CA, so service-a can authenticate
 * itself with the shared internal client identity.
 *
 * <p>service-a does NOT re-create the {@code X-ZTE-User-Context} token; it forwards
 * the header received from the gateway unchanged. This is the delegation chain
 * (not re-issuance) — the gateway is the sole issuer of OBO tokens.
 */
@Configuration
public class ServiceBClientConfig {

    @Bean
    public ReloadableSslContextFactory serviceBSslContextFactory(
            @Value("${zte.mtls.certs-dir:./certs}") String certsDir,
            @Value("${zte.mtls.key-password}") String keyPassword) {
        return new ReloadableSslContextFactory(
                certsDir + "/client.p12",
                certsDir + "/truststore.p12",
                keyPassword
        );
    }

    @Bean
    public WebClient serviceBWebClient(
            @Value("${service-b.uri:https://localhost:8082}") String serviceBUri,
            ReloadableSslContextFactory serviceBSslContextFactory) {

        HttpClient httpClient = HttpClient.create()
                .responseTimeout(Duration.ofSeconds(10))
                .secure(spec -> spec.sslContext(serviceBSslContextFactory.current()));

        return WebClient.builder()
                .baseUrl(serviceBUri)
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
    }

    @Bean
    public UserContextTokenService userContextTokenService(
            @Value("${zte.obo.secret}") String secret,
            @Value("${zte.obo.expiry-seconds:30}") long expirySeconds) {
        return new UserContextTokenService(secret, expirySeconds);
    }
}
