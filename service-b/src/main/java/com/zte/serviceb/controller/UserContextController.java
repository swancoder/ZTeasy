package com.zte.serviceb.controller;

import com.zte.auth.audit.ZteAuditLogger;
import com.zte.auth.obo.UserContextClaims;
import com.zte.auth.obo.UserContextTokenService;
import io.jsonwebtoken.JwtException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Service B endpoint — validates the {@code X-ZTE-User-Context} OBO token and
 * returns the verified user context.
 *
 * <p>By the time a request reaches this controller, two layers of trust have
 * already been established:
 * <ol>
 *   <li><strong>mTLS (transport):</strong> The TLS handshake verified that the
 *       caller presented a certificate signed by the ZTE CA ({@code client.p12}).
 *       Spring Boot's Netty server rejected the connection outright if the cert
 *       was absent or untrusted — no request even reached a controller in that case.</li>
 *   <li><strong>OBO token (application):</strong> The {@code X-ZTE-User-Context}
 *       header is a compact JWT signed with the shared {@code ZTE_OBO_SECRET}.
 *       Validating it here proves that the <em>gateway</em> created this delegation
 *       context for a specific user, roles, and time window (30 s TTL).</li>
 * </ol>
 *
 * <p>Together, mTLS proves <em>which internal service</em> is calling, and the OBO
 * token proves <em>which end-user</em> the call is on behalf of.
 */
@RestController
public class UserContextController {

    private static final Logger log = LoggerFactory.getLogger(UserContextController.class);

    private final UserContextTokenService tokenService;

    public UserContextController(UserContextTokenService tokenService) {
        this.tokenService = tokenService;
    }

    /**
     * Validates the OBO token and returns the verified user context.
     *
     * @param userContext the {@code X-ZTE-User-Context} JWT (required)
     * @param callerId    the {@code X-User-Id} plain user UUID (optional, for logging)
     * @return {@code 200} with user context, or {@code 401} if the token is invalid/expired
     */
    @GetMapping("/api/v1/service-b/context")
    public Mono<ResponseEntity<Map<String, Object>>> userContext(
            @RequestHeader(value = "X-ZTE-User-Context", required = false) String userContext,
            @RequestHeader(value = "X-User-Id",          required = false) String callerId) {

        if (userContext == null || userContext.isBlank()) {
            log.warn("Missing X-ZTE-User-Context header");
            return Mono.just(ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Missing X-ZTE-User-Context header")));
        }

        try {
            UserContextClaims claims = tokenService.validateAndParse(userContext);
            log.debug("OBO token validated: sub={} roles={}", claims.sub(), claims.roles());
            ZteAuditLogger.oboValidated("service-b", claims.sub(), claims.roles().toString());

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("service",    "service-b");
            body.put("sub",        claims.sub());
            body.put("roles",      claims.roles());
            body.put("issuedAt",   claims.issuedAt().toString());
            body.put("expiresAt",  claims.expiresAt().toString());
            body.put("trustBasis", "mTLS (ZTE-CA) + HMAC-SHA256 OBO token");

            return Mono.just(ResponseEntity.ok(body));

        } catch (JwtException e) {
            log.warn("Invalid X-ZTE-User-Context from caller={}: {}", callerId, e.getMessage());
            ZteAuditLogger.oboRejected("service-b", e.getMessage());
            return Mono.just(ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Invalid or expired X-ZTE-User-Context: " + e.getMessage())));
        }
    }

    /**
     * A second, more sensitive endpoint (ADR-017) — same mTLS + OBO trust
     * basis as {@link #userContext}, deliberately <em>not</em> granted to
     * {@code service-a} in {@code zte-policies.yaml}'s {@code
     * service2service} rules, so the gateway's {@code
     * ServiceToServiceAuthorizationFilter} denies it with {@code 403}
     * before the request ever reaches this method — service-b's own
     * validation logic here is identical to {@link #userContext}'s and
     * exists only so the two endpoints have comparable trust posture, not
     * because this method itself implements the access restriction.
     */
    @GetMapping("/api/v1/service-b/restricted")
    public Mono<ResponseEntity<Map<String, Object>>> restricted(
            @RequestHeader(value = "X-ZTE-User-Context", required = false) String userContext,
            @RequestHeader(value = "X-User-Id",          required = false) String callerId) {

        if (userContext == null || userContext.isBlank()) {
            log.warn("Missing X-ZTE-User-Context header (restricted)");
            return Mono.just(ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Missing X-ZTE-User-Context header")));
        }

        try {
            UserContextClaims claims = tokenService.validateAndParse(userContext);
            log.debug("OBO token validated for /restricted: sub={} roles={}", claims.sub(), claims.roles());
            ZteAuditLogger.oboValidated("service-b", claims.sub(), claims.roles().toString());

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("service", "service-b");
            body.put("resource", "restricted");
            body.put("sub", claims.sub());
            body.put("message", "If you can see this, ServiceToServiceAuthorizationFilter let you through.");

            return Mono.just(ResponseEntity.ok(body));

        } catch (JwtException e) {
            log.warn("Invalid X-ZTE-User-Context from caller={} (restricted): {}", callerId, e.getMessage());
            ZteAuditLogger.oboRejected("service-b", e.getMessage());
            return Mono.just(ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Invalid or expired X-ZTE-User-Context: " + e.getMessage())));
        }
    }
}
