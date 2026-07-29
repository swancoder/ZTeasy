package com.zte.gateway.mcp;

import org.junit.jupiter.api.Test;
import org.springframework.http.codec.ServerSentEvent;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link McpSessionManager}.
 */
class McpSessionManagerTest {

    private final McpSessionManager manager = new McpSessionManager();

    @Test
    void emitsIntoOpenSession() {
        var stream = manager.open("session-1");
        assertThat(manager.exists("session-1")).isTrue();

        ServerSentEvent<String> event = ServerSentEvent.<String>builder().event("message").data("hello").build();

        StepVerifier.create(stream)
                .then(() -> manager.emit("session-1", event))
                .assertNext(received -> assertThat(received.data()).isEqualTo("hello"))
                .then(() -> manager.close("session-1"))
                .verifyComplete();

        assertThat(manager.exists("session-1")).isFalse();
    }

    @Test
    void emitToUnknownSessionIsANoOp() {
        // Must not throw — the handler treats an unknown sessionId as caller error
        // before ever calling emit(), but emit() itself stays defensive.
        manager.emit("does-not-exist", ServerSentEvent.<String>builder().data("x").build());
        assertThat(manager.exists("does-not-exist")).isFalse();
    }

    @Test
    void closeIsIdempotent() {
        manager.open("session-2");
        manager.close("session-2");
        manager.close("session-2"); // second close must not throw
        assertThat(manager.exists("session-2")).isFalse();
    }
}
