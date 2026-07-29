package com.zte.gateway.mcp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Maps an MCP session id (established on {@code GET /sse}) to the live
 * {@link Sinks.Many} feeding that session's SSE stream.
 *
 * <p>This is what makes it possible for {@code POST /message} — a completely
 * separate HTTP request/response pair — to inject a result (allow or deny) into
 * a client's already-open SSE connection: neither Spring Cloud Gateway's routing
 * nor a plain request/response handler has a way to reach across requests like
 * this, so this registry exists specifically to bridge them.
 */
@Component
public class McpSessionManager {

    private static final Logger log = LoggerFactory.getLogger(McpSessionManager.class);

    private final Map<String, Sinks.Many<ServerSentEvent<String>>> sessions = new ConcurrentHashMap<>();

    /**
     * Opens a new session and returns its event stream. The session is removed
     * automatically when the stream terminates (client disconnect, error, or
     * explicit {@link #close}).
     */
    public Flux<ServerSentEvent<String>> open(String sessionId) {
        Sinks.Many<ServerSentEvent<String>> sink = Sinks.many().multicast().onBackpressureBuffer();
        sessions.put(sessionId, sink);
        log.debug("MCP session opened: {}", sessionId);
        return sink.asFlux().doFinally(signal -> close(sessionId));
    }

    public boolean exists(String sessionId) {
        return sessions.containsKey(sessionId);
    }

    /** Emits an event into the given session's SSE stream. No-op if the session is unknown. */
    public void emit(String sessionId, ServerSentEvent<String> event) {
        Sinks.Many<ServerSentEvent<String>> sink = sessions.get(sessionId);
        if (sink == null) {
            log.warn("Attempted to emit into unknown MCP session: {}", sessionId);
            return;
        }
        Sinks.EmitResult result = sink.tryEmitNext(event);
        if (result.isFailure()) {
            log.warn("Failed to emit into MCP session {}: {}", sessionId, result);
        }
    }

    public void close(String sessionId) {
        Sinks.Many<ServerSentEvent<String>> sink = sessions.remove(sessionId);
        if (sink != null) {
            sink.tryEmitComplete();
            log.debug("MCP session closed: {}", sessionId);
        }
    }
}
