package com.zte.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * ZTE-Lightweight API Gateway.
 *
 * <p>Single ingress point enforcing Zero Trust: every request must carry a valid
 * JWT issued by Keycloak before being forwarded to a downstream service.
 *
 * <p>Architecture: Spring Cloud Gateway (WebFlux reactive) + Spring Security
 * OAuth2 Resource Server (JWT validation via Keycloak JWKS endpoint).
 *
 * <p><strong>{@code @EnableScheduling} lives here (ADR-017), not on {@code
 * MtlsHttpClientConfig}</strong> (where it previously was) — found live while
 * debugging why {@code InventoryRouteRefreshScheduler}'s periodic {@code
 * RefreshRoutesEvent} never fired during integration tests: {@code
 * MtlsHttpClientConfig} is {@code @ConditionalOnProperty(zte.mtls.enabled)},
 * and the {@code it} test profile sets that {@code false} — meaning the whole
 * class, {@code @EnableScheduling} included, was never registered as a bean
 * in any integration test's context. Every {@code @Scheduled} method in this
 * application (also {@code HealthPollingService}'s, pre-existing) has never
 * actually run during a test until this was found and fixed; it simply never
 * mattered before, since nothing previously observable in a test depended on
 * a periodic job actually firing rather than an explicit synchronous trigger.
 */
@SpringBootApplication(scanBasePackages = {"com.zte.gateway", "com.zte.auth"})
@EnableScheduling
public class GatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
