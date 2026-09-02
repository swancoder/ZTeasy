package com.zte.gateway.me;

import com.zte.gateway.audit.RequestLog;
import com.zte.gateway.audit.RequestLogRepository;
import com.zte.gateway.metering.LlmMeteringService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * What the gateway decided about <em>you</em> (Stage 39, ADR-039).
 *
 * <p>The Admin Console's audit trail answers "what happened", for an operator.
 * This answers "what happened to me", for the person it happened to — the feed
 * behind the chat application's trace panel, where a refusal appears next to the
 * message that caused it.
 *
 * <p>The scoping is done in SQL, not after reading: an endpoint that fetches
 * everyone's rows and then filters is one mistake away from being a data leak,
 * and this one is reachable by every interactive user.
 */
@RestController
@RequestMapping("/api/v1/me")
class MyEventsController {

    private final RequestLogRepository requestLogs;
    private final LlmMeteringService metering;

    MyEventsController(RequestLogRepository requestLogs, LlmMeteringService metering) {
        this.requestLogs = requestLogs;
        this.metering = metering;
    }

    /**
     * @param limit capped, because this is a live panel polling on a timer, not an export
     */
    @GetMapping("/events")
    public Mono<List<Event>> events(@AuthenticationPrincipal Jwt jwt,
                                     @RequestParam(defaultValue = "50") int limit) {
        String me = username(jwt);
        return requestLogs.findOwnEvents(me, Math.min(Math.max(limit, 1), 200))
                .map(Event::of)
                .collectList();
    }

    /** This person's own model spend — the same numbers the executive dashboard totals. */
    @GetMapping("/spend")
    public Mono<Map<String, Object>> spend(@AuthenticationPrincipal Jwt jwt,
                                            @RequestParam(defaultValue = "24") int hours) {
        String me = username(jwt);
        return metering.spendByAgent(hours).map(all -> all.stream()
                .filter(s -> me.equals(s.agentId()))
                .findFirst()
                .map(s -> Map.<String, Object>of("agentId", s.agentId(), "inputTokens", s.inputTokens(),
                        "outputTokens", s.outputTokens(), "costMicros", s.costMicros(), "calls", s.calls()))
                .orElse(Map.of("agentId", me, "inputTokens", 0, "outputTokens", 0, "costMicros", 0, "calls", 0)));
    }

    private static String username(Jwt jwt) {
        String u = jwt == null ? null : jwt.getClaimAsString("preferred_username");
        return u == null || u.isBlank() ? "unknown" : u;
    }

    /**
     * A row as the panel shows it. Trimmed on purpose: the trace is meant to be
     * read next to a chat message, and the columns an operator needs (client IP,
     * user agent, process id) would crowd out the decision itself.
     */
    record Event(String timestamp, String decision, String tool, String target, String path,
                  Integer statusCode, String reason, String traceId) {

        static Event of(RequestLog r) {
            return new Event(
                    r.timestamp() == null ? Instant.now().toString() : r.timestamp().toString(),
                    r.decisionEffect(), r.toolName(), r.targetService(), r.path(),
                    r.statusCode(), r.message(), r.traceId());
        }
    }
}
