package com.zte.gateway.chat;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatchers;
import org.springframework.web.reactive.config.ResourceHandlerRegistry;
import org.springframework.web.reactive.config.WebFluxConfigurer;

/**
 * Serves the Chat Console SPA at {@code /chat/} (Stage 39, ADR-039) — the same
 * arrangement {@code ApproverUiConfig} uses for {@code /approver/}.
 *
 * <p>The static bundle is public, as static bundles are: it contains no data and
 * no credential, and everything it can reach — the chat API, the model, a person's
 * own trace — requires a token and a policy decision. What separates this console
 * from the other two is not who may download the JavaScript, but the realm role
 * that its API paths demand.
 */
@Configuration
public class ChatUiConfig implements WebFluxConfigurer {

    @Bean
    @Order(-88)
    public SecurityWebFilterChain chatUiSecurityChain(ServerHttpSecurity http) {
        return http
                .securityMatcher(ServerWebExchangeMatchers.pathMatchers("/chat/**"))
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .authorizeExchange(ex -> ex.anyExchange().permitAll())
                .build();
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/chat/**")
                .addResourceLocations("classpath:/static/chat/")
                .resourceChain(true);
    }
}
