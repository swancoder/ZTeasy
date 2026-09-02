package com.zte.gateway.llm;

import com.zte.gateway.metering.LlmMeteringService;
import com.zte.gateway.metering.LlmUsage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/** The money path: what the gateway records when it makes a model call for someone (ADR-039). */
@ExtendWith(MockitoExtension.class)
class LlmEgressControllerTest {

    @Mock LlmMeteringService metering;

    private static final String RESPONSE = """
            {"id":"msg_1","model":"claude-sonnet-4-6","role":"assistant",
             "content":[{"type":"text","text":"hello"}],
             "usage":{"input_tokens":1200,"output_tokens":300}}
            """;

    private Jwt person(String username) {
        return Jwt.withTokenValue("t").header("alg", "none")
                .claim("preferred_username", username)
                .claim("azp", "zte-chat-ui")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60))
                .build();
    }

    private LlmEgressController controller(String apiKey, WebClient.Builder builder) {
        return new LlmEgressController(builder, metering, "https://api.example",
                apiKey, "2023-06-01", 30, 2760, 13800);
    }

    private WebClient.Builder responding(String body) {
        return WebClient.builder().exchangeFunction(req -> Mono.just(
                ClientResponse.create(HttpStatus.OK)
                        .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                        .body(body).build()));
    }

    /**
     * Spend is attributed to the human, not to the application that made the HTTP
     * call — the whole reason the gateway is in this path rather than each app
     * reporting its own totals.
     */
    @Test
    void recordsSpendAgainstThePerson_readFromTheVendorsOwnResponse() {
        StepVerifier.create(controller("sk-test", responding(RESPONSE)).messages(Map.of("model", "x"), person("anna")))
                .assertNext(r -> assertThat(r.getStatusCode().value()).isEqualTo(200))
                .verifyComplete();

        ArgumentCaptor<LlmUsage> usage = ArgumentCaptor.forClass(LlmUsage.class);
        verify(metering).record(usage.capture());
        assertThat(usage.getValue().agentId()).isEqualTo("anna");
        assertThat(usage.getValue().inputTokens()).isEqualTo(1200);
        assertThat(usage.getValue().outputTokens()).isEqualTo(300);
        // 1200/1000*2760 + 300/1000*13800
        assertThat(usage.getValue().costMicros()).isEqualTo(3312 + 4140);
        assertThat(usage.getValue().purpose()).isEqualTo("chat");
    }

    /**
     * No credential means no call — never a fallback to one the caller supplied.
     * The point of routing through here is that applications hold no model key.
     */
    @Test
    void withoutAGatewayCredential_theCallIsRefused_notForwarded() {
        WebClient.Builder mustNotBeUsed = WebClient.builder()
                .exchangeFunction(req -> Mono.error(new AssertionError("no key must mean no call")));

        StepVerifier.create(controller("", mustNotBeUsed).messages(Map.of(), person("anna")))
                .assertNext(r -> assertThat(r.getStatusCode().value()).isEqualTo(503))
                .verifyComplete();

        verifyNoInteractions(metering);
    }

    /** A response we cannot measure is passed through, but nothing is invented for the bill. */
    @Test
    void responseWithoutUsage_isReturnedButNotMetered() {
        StepVerifier.create(controller("sk-test", responding("{\"id\":\"msg_2\",\"content\":[]}"))
                        .messages(Map.of(), person("anna")))
                .assertNext(r -> assertThat(r.getStatusCode().value()).isEqualTo(200))
                .verifyComplete();

        verifyNoInteractions(metering);
    }

    @Test
    void vendorFailure_surfacesAsBadGateway_andCostsNothing() {
        WebClient.Builder failing = WebClient.builder()
                .exchangeFunction(req -> Mono.just(ClientResponse.create(HttpStatus.TOO_MANY_REQUESTS).build()));

        StepVerifier.create(controller("sk-test", failing).messages(Map.of(), person("anna")))
                .assertNext(r -> assertThat(r.getStatusCode().value()).isEqualTo(502))
                .verifyComplete();

        verifyNoInteractions(metering);
    }
}
