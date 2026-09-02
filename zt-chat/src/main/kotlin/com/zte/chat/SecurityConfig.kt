package com.zte.chat

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity
import org.springframework.security.config.web.server.ServerHttpSecurity
import org.springframework.security.web.server.SecurityWebFilterChain

/**
 * This service sits behind the gateway, which has already decided that the caller
 * may reach it — and it validates the token anyway (ADR-018's posture: the network
 * is not an authority). The user's token is also what it presents when calling back
 * through the gateway, so a request that arrives without one cannot be served at all.
 */
@Configuration
@EnableWebFluxSecurity
class SecurityConfig {

    @Bean
    fun securityWebFilterChain(http: ServerHttpSecurity): SecurityWebFilterChain =
        http
            .csrf { it.disable() }   // stateless bearer-token API, no cookies to forge
            .authorizeExchange {
                it.pathMatchers("/actuator/health").permitAll()
                it.anyExchange().authenticated()
            }
            .oauth2ResourceServer { it.jwt { } }
            .build()
}
