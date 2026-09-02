package com.zte.gateway.llm;

import com.zte.gateway.metering.LlmMeteringService;
import com.zte.gateway.metering.LlmUsage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Map;

/**
 * The gateway as the perimeter's exit to a language model (Stage 39, ADR-039).
 *
 * <p>Everything about an agent's tool use is governed here; the model call itself
 * was not. Each application held its own vendor key, called the vendor directly,
 * and reported its own token usage afterwards — spend figures that the gateway
 * took on trust from the very component whose spend they described.
 *
 * <p>Routing the call through the gate changes three things at once:
 * <ul>
 *   <li><b>The key never leaves the gateway.</b> A chat backend that cannot make a
 *       model call except through here cannot make one the perimeter does not see,
 *       and a compromised front end leaks no vendor credential.</li>
 *   <li><b>Spend is measured, not declared.</b> Token counts are read out of the
 *       vendor's own response by the party that will bill for them.</li>
 *   <li><b>A model call is a policy decision</b> like any other: the same
 *       {@code users2service} rules decide who may make one, and the same audit
 *       trail records that they did.</li>
 * </ul>
 *
 * <p>The request body is passed through unchanged apart from the key, so this is a
 * gate rather than a translation layer — one fewer thing to keep in step with a
 * vendor API. The coupling that does exist is the {@code usage} block it reads for
 * metering, and that is the price of measuring instead of trusting.
 */
@RestController
@RequestMapping("/api/v1/llm")
public class LlmEgressController {

    private static final Logger log = LoggerFactory.getLogger(LlmEgressController.class);

    private final WebClient webClient;
    private final LlmMeteringService metering;
    private final String apiKey;
    private final String anthropicVersion;
    private final long inputMicrosPer1k;
    private final long outputMicrosPer1k;

    public LlmEgressController(WebClient.Builder builder,
                                LlmMeteringService metering,
                                @Value("${zte.llm.base-uri:https://api.anthropic.com}") String baseUri,
                                @Value("${zte.llm.api-key:}") String apiKey,
                                @Value("${zte.llm.anthropic-version:2023-06-01}") String anthropicVersion,
                                @Value("${zte.llm.timeout-seconds:120}") long timeoutSeconds,
                                @Value("${anthropic.pricing.input-micros-per-1k:2760}") long inputMicrosPer1k,
                                @Value("${anthropic.pricing.output-micros-per-1k:13800}") long outputMicrosPer1k) {
        this.metering = metering;
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.anthropicVersion = anthropicVersion;
        this.inputMicrosPer1k = inputMicrosPer1k;
        this.outputMicrosPer1k = outputMicrosPer1k;
        this.webClient = builder.baseUrl(baseUri)
                .clientConnector(new org.springframework.http.client.reactive.ReactorClientHttpConnector(
                        reactor.netty.http.client.HttpClient.create()
                                .responseTimeout(Duration.ofSeconds(timeoutSeconds))))
                .build();
    }

    /**
     * Proxies one {@code /v1/messages} call. Authorisation happened before this
     * method ran — {@code AdminAuthorizationFilter} evaluates the same YAML rules
     * for this prefix as for every other gateway-local API — so reaching here
     * means a policy said this person may spend money on a model.
     */
    @PostMapping(value = "/messages", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<Object>> messages(@RequestBody Map<String, Object> body,
                                                  @AuthenticationPrincipal Jwt jwt) {
        if (apiKey.isEmpty()) {
            // Fail loudly rather than fall back to a per-application key: the whole
            // point is that no other component holds one (ADR-037/ADR-039).
            return Mono.just(ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "no model credential configured on the gateway (zte.llm.api-key)")));
        }
        String caller = callerName(jwt);
        return webClient.post()
                .uri("/v1/messages")
                .header("x-api-key", apiKey)
                .header("anthropic-version", anthropicVersion)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(Map.class)
                .<ResponseEntity<Object>>map(response -> {
                    meter(caller, response);
                    return ResponseEntity.ok(response);
                })
                .onErrorResume(e -> {
                    log.warn("[ZTE-LLM] call for {} failed: {}", caller, e.toString());
                    return Mono.just(ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                            .body(Map.of("error", "model call failed: " + e.getMessage())));
                });
    }

    @SuppressWarnings("unchecked")
    private void meter(String caller, Map<String, Object> response) {
        Object usage = response.get("usage");
        if (!(usage instanceof Map<?, ?> u)) {
            // A response we cannot measure is still a response; recording nothing is
            // better than recording a guess, but it must be visible that we couldn't.
            log.warn("[ZTE-LLM] no usage block in the model response for {} — spend not recorded", caller);
            return;
        }
        long in = asLong(((Map<String, Object>) u).get("input_tokens"));
        long out = asLong(((Map<String, Object>) u).get("output_tokens"));
        long costMicros = (in * inputMicrosPer1k / 1000) + (out * outputMicrosPer1k / 1000);
        String model = String.valueOf(response.getOrDefault("model", "unknown"));
        metering.record(LlmUsage.of(caller, model, in, out, costMicros, "chat"));
        log.info("[ZTE-LLM] {} spent {} in/{} out tokens on {} ({} micros)", caller, in, out, model, costMicros);
    }

    private static long asLong(Object v) {
        return v instanceof Number n ? n.longValue() : 0L;
    }

    /** The person, so spend is attributable to a human rather than to an application. */
    private static String callerName(Jwt jwt) {
        if (jwt == null) {
            return "unknown";
        }
        String username = jwt.getClaimAsString("preferred_username");
        if (username != null && !username.isBlank()) {
            return username;
        }
        String azp = jwt.getClaimAsString("azp");
        return azp != null ? azp : jwt.getSubject();
    }
}
