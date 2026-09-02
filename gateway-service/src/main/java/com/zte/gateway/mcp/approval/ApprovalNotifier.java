package com.zte.gateway.mcp.approval;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Tells a human that something is waiting (Stage 35, ADR-035).
 *
 * <p>Everything else in this queue assumes someone is looking at the page. This is
 * the one path that reaches a person who is not — an outbound POST to a configured
 * URL (Slack and Teams incoming webhooks both render the {@code text} field and
 * ignore the rest, so one body serves them and a generic JSON consumer alike).
 *
 * <p><strong>The payload never contains the call's arguments.</strong> Those are
 * precisely the sensitive part — the recipient, subject and body of a customer
 * email — and this hop leaves the perimeter for a third party. A system that masks
 * fields in MCP responses (ADR-032) and then posts the same content to a chat
 * workspace would be inconsistent to the point of dishonesty. The message carries
 * who, what, why, by when, and a link back to the place where the arguments can be
 * read by someone who has authenticated.
 *
 * <p>Failures never touch the held call: the approval is already durable, and a
 * chat outage must not turn into a policy outage. They are recorded instead, so
 * "nobody was told" is answerable after the fact.
 */
@Component
public class ApprovalNotifier {

    private static final Logger log = LoggerFactory.getLogger(ApprovalNotifier.class);

    private final ApprovalAudience audience;
    private final ApprovalNotificationRepository notifications;
    private final WebClient webClient;
    private final String webhookUrl;
    private final Duration timeout;
    private final String linkBase;

    public ApprovalNotifier(ApprovalAudience audience, ApprovalNotificationRepository notifications,
                             WebClient.Builder builder,
                             @Value("${zte.approvals.webhook.url:}") String webhookUrl,
                             @Value("${zte.approvals.webhook.timeout-ms:5000}") long timeoutMs,
                             @Value("${zte.approvals.link-base:}") String linkBase) {
        this.audience = audience;
        this.notifications = notifications;
        this.webhookUrl = webhookUrl == null ? "" : webhookUrl.trim();
        this.linkBase = linkBase == null ? "" : linkBase.trim().replaceAll("/+$", "");
        // Timeout applied per exchange rather than baked into a connector, so the
        // injected builder stays the one the caller configured — which is also what
        // makes this class testable without a socket.
        this.timeout = Duration.ofMillis(timeoutMs);
        this.webClient = builder.build();
    }

    /** Fire-and-forget: returns immediately, the caller's held call does not wait on a chat server. */
    public void notifyRaised(PendingApproval approval) {
        deliver(approval).subscribe();
    }

    /**
     * A reminder for an approval that is running out of time (ADR-036).
     *
     * <p>Claim first, send second. Both gateway apps run the reminder scheduler, and
     * unlike expiry — which defends itself by changing the approval's status — a
     * reminder leaves the approval untouched, so nothing would stop two instances
     * from each sending, once per interval, until the deadline. The claim row is
     * written under a unique index; the instance that loses that race gets a
     * duplicate-key error and stops, having sent nothing.
     */
    Mono<ApprovalNotification> remind(PendingApproval approval, String stage, long secondsRemaining) {
        return audience.resolve(approval).flatMap(aud -> {
            String recipients = String.join(",", aud.members());
            return notifications.save(ApprovalNotification.reminderClaim(approval.id(), stage, aud.urn(), recipients))
                    .onErrorResume(DuplicateKeyException.class, e -> {
                        log.debug("[ZTE-APPROVAL] reminder {} for {} already claimed elsewhere", stage, approval.id());
                        return Mono.empty();
                    })
                    .flatMap(claim -> send(approval, aud, claim, secondsRemaining));
        });
    }

    private Mono<ApprovalNotification> send(PendingApproval approval, ApprovalAudience.Audience aud,
                                             ApprovalNotification claim, long secondsRemaining) {
        if (webhookUrl.isEmpty()) {
            return notifications.save(claim.settled(ApprovalNotification.Status.SKIPPED,
                    "no zte.approvals.webhook.url configured"));
        }
        if (!aud.deliverable()) {
            return notifications.save(claim.settled(ApprovalNotification.Status.SKIPPED,
                    "audience '" + aud.urn() + "' resolves to nobody"));
        }
        return webClient.post()
                .uri(webhookUrl)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(reminderBody(approval, aud, secondsRemaining))
                .retrieve()
                .toBodilessEntity()
                .timeout(timeout)
                .flatMap(r -> notifications.save(claim.settled(ApprovalNotification.Status.SENT,
                        "HTTP " + r.getStatusCode().value())))
                .onErrorResume(e -> {
                    log.warn("[ZTE-APPROVAL] reminder for {} failed: {}", approval.id(), e.toString());
                    return notifications.save(claim.settled(ApprovalNotification.Status.FAILED, e.toString()));
                });
    }

    /** Same discipline as {@link #body} — no arguments — with the time pressure made explicit. */
    Map<String, Object> reminderBody(PendingApproval approval, ApprovalAudience.Audience aud,
                                      long secondsRemaining) {
        Map<String, Object> body = body(approval, aud);
        long minutes = Math.max(0, secondsRemaining / 60);
        body.put("text", "Reminder — still undecided with " + minutes + " min left: " + body.get("text"));
        @SuppressWarnings("unchecked")
        Map<String, Object> detail = (Map<String, Object>) body.get("approval");
        detail.put("reminder", true);
        detail.put("secondsRemaining", secondsRemaining);
        return body;
    }

    Mono<ApprovalNotification> deliver(PendingApproval approval) {
        return audience.resolve(approval).flatMap(aud -> {
            String recipients = String.join(",", aud.members());
            if (webhookUrl.isEmpty()) {
                return record(approval, aud.urn(), recipients, ApprovalNotification.Status.SKIPPED,
                        "no zte.approvals.webhook.url configured");
            }
            if (!aud.deliverable()) {
                return record(approval, aud.urn(), recipients, ApprovalNotification.Status.SKIPPED,
                        "audience '" + aud.urn() + "' resolves to nobody");
            }
            return webClient.post()
                    .uri(webhookUrl)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body(approval, aud))
                    .retrieve()
                    .toBodilessEntity()
                    .timeout(timeout)
                    .flatMap(r -> record(approval, aud.urn(), recipients, ApprovalNotification.Status.SENT,
                            "HTTP " + r.getStatusCode().value()))
                    .onErrorResume(e -> {
                        log.warn("[ZTE-APPROVAL] notification for {} failed: {}", approval.id(), e.toString());
                        return record(approval, aud.urn(), recipients, ApprovalNotification.Status.FAILED,
                                e.toString());
                    });
        });
    }

    private Mono<ApprovalNotification> record(PendingApproval approval, String urn, String recipients,
                                               ApprovalNotification.Status status, String detail) {
        return notifications.save(ApprovalNotification.of(approval.id(), urn, recipients, status, detail));
    }

    /**
     * {@code text} for chat products that render exactly that field; {@code approval}
     * for anything that wants structure. Deliberately no {@code argumentsJson}.
     */
    Map<String, Object> body(PendingApproval approval, ApprovalAudience.Audience aud) {
        String link = linkBase.isEmpty() ? null : linkBase + "/approver/index.html";
        String who = aud.members().isEmpty() ? aud.urn() : aud.urn() + " (" + String.join(", ", aud.members()) + ")";
        StringBuilder text = new StringBuilder()
                .append("ZTeasy: agent '").append(approval.agentId()).append("' is held on '")
                .append(approval.toolName()).append("' and needs a decision from ").append(who).append(".");
        if (approval.expiresAt() != null) {
            text.append(" Expires ").append(approval.expiresAt()).append(".");
        }
        if (link != null) {
            text.append(" ").append(link);
        }

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("id", String.valueOf(approval.id()));
        detail.put("agentId", approval.agentId());
        detail.put("toolName", approval.toolName());
        detail.put("reason", approval.reason());
        detail.put("routeTo", approval.routeTo());
        detail.put("addressedTo", aud.urn());
        detail.put("recipients", aud.members());
        detail.put("requestedAt", String.valueOf(approval.requestedAt()));
        detail.put("expiresAt", String.valueOf(approval.expiresAt()));
        if (link != null) {
            detail.put("link", link);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("text", text.toString());
        body.put("approval", detail);
        return body;
    }
}
