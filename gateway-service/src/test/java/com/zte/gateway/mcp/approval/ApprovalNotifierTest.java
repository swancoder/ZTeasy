package com.zte.gateway.mcp.approval;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Notification delivery and, above all, what the payload is allowed to contain (Stage 35, ADR-035). */
@ExtendWith(MockitoExtension.class)
class ApprovalNotifierTest {

    @Mock ApprovalAudience               audience;
    @Mock ApprovalNotificationRepository notifications;

    private static final String SECRET_ARGS =
            "{\"to\":\"rep@nordwind.example\",\"subject\":\"Renewal\",\"body\":\"Your contract expires\"}";

    private PendingApproval approval() {
        return new PendingApproval(UUID.randomUUID(), "session-1", "crm-account-health-emea-01", "send_email",
                "7", SECRET_ARGS, "role:APPROVER", "held by rule mcp-hold-send-email", "PENDING",
                Instant.parse("2026-09-02T10:00:00Z"), Instant.parse("2026-09-03T10:00:00Z"),
                null, null, "trace-1", "203.0.113.10", "agent/1.0", "CRM Account Health");
    }

    private ApprovalNotifier notifier(String url, WebClient.Builder builder) {
        return new ApprovalNotifier(audience, notifications, builder, url, 5000, "https://demo.zteasy.tech");
    }

    private WebClient.Builder respondingWith(HttpStatus status) {
        return WebClient.builder().exchangeFunction(req -> Mono.just(ClientResponse.create(status).build()));
    }

    private ApprovalNotification captureSaved() {
        ArgumentCaptor<ApprovalNotification> saved = ArgumentCaptor.forClass(ApprovalNotification.class);
        verify(notifications).save(saved.capture());
        return saved.getValue();
    }

    @Test
    void delivers_andRecordsSent() {
        when(audience.resolve(any())).thenReturn(Mono.just(
                new ApprovalAudience.Audience("role:APPROVER", List.of("zte-admin", "zte-dpo"), true)));
        when(notifications.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        StepVerifier.create(notifier("https://hooks.example/abc", respondingWith(HttpStatus.OK))
                        .deliver(approval()))
                .expectNextCount(1)
                .verifyComplete();

        ApprovalNotification saved = captureSaved();
        assertThat(saved.status()).isEqualTo("SENT");
        assertThat(saved.audience()).isEqualTo("role:APPROVER");
        assertThat(saved.recipients()).isEqualTo("zte-admin,zte-dpo");
    }

    /**
     * The point of the whole design. This hop leaves the perimeter for a third
     * party, and the held call's arguments are exactly the sensitive part — the
     * recipient, subject and body of a customer email. A system that masks fields
     * in MCP responses and then posts them to a chat workspace would be lying
     * about what it protects.
     */
    @Test
    void payload_carriesWhoWhatWhenAndALink_butNeverTheArguments() {
        ApprovalAudience.Audience aud =
                new ApprovalAudience.Audience("role:APPROVER", List.of("zte-dpo"), true);

        Map<String, Object> body = notifier("https://hooks.example/abc", WebClient.builder())
                .body(approval(), aud);

        String rendered = body.toString();
        assertThat(rendered).doesNotContain("rep@nordwind.example");
        assertThat(rendered).doesNotContain("Your contract expires");
        assertThat(rendered).doesNotContain("Renewal");

        assertThat((String) body.get("text"))
                .contains("crm-account-health-emea-01")
                .contains("send_email")
                .contains("zte-dpo")
                .contains("https://demo.zteasy.tech/approver/index.html");

        @SuppressWarnings("unchecked")
        Map<String, Object> detail = (Map<String, Object>) body.get("approval");
        assertThat(detail).containsKeys("id", "agentId", "toolName", "expiresAt", "link");
        assertThat(detail).doesNotContainKey("argumentsJson");
    }

    @Test
    void webhookFailure_isRecorded_notSwallowed() {
        when(audience.resolve(any())).thenReturn(Mono.just(
                new ApprovalAudience.Audience("role:APPROVER", List.of("zte-dpo"), true)));
        when(notifications.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        StepVerifier.create(notifier("https://hooks.example/abc", respondingWith(HttpStatus.INTERNAL_SERVER_ERROR))
                        .deliver(approval()))
                .expectNextCount(1)
                .verifyComplete();

        assertThat(captureSaved().status()).isEqualTo("FAILED");
    }

    /** "Nobody was told because nothing is configured" is an answer, so it is stored as one. */
    @Test
    void noWebhookConfigured_recordsSkipped_ratherThanSilence() {
        when(audience.resolve(any())).thenReturn(Mono.just(
                new ApprovalAudience.Audience("role:APPROVER", List.of("zte-dpo"), true)));
        when(notifications.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        StepVerifier.create(notifier("", WebClient.builder()).deliver(approval()))
                .expectNextCount(1)
                .verifyComplete();

        ApprovalNotification saved = captureSaved();
        assertThat(saved.status()).isEqualTo("SKIPPED");
        assertThat(saved.detail()).contains("no zte.approvals.webhook.url");
    }

    /** A rule may route to a role nobody holds; the call then has no addressee, and that is recorded. */
    @Test
    void audienceResolvingToNobody_recordsSkippedWithTheUrn() {
        when(audience.resolve(any())).thenReturn(Mono.just(
                new ApprovalAudience.Audience("role:NOBODY_HOLDS_THIS", List.of(), false)));
        when(notifications.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        StepVerifier.create(notifier("https://hooks.example/abc", respondingWith(HttpStatus.OK))
                        .deliver(approval()))
                .expectNextCount(1)
                .verifyComplete();

        ApprovalNotification saved = captureSaved();
        assertThat(saved.status()).isEqualTo("SKIPPED");
        assertThat(saved.detail()).contains("role:NOBODY_HOLDS_THIS").contains("nobody");
    }

    /**
     * The claim is written before anything is sent, so the instance that loses the
     * unique-index race sends nothing at all. Without this, both gateway apps would
     * remind the same person every interval until the deadline — expiry is safe
     * from that only because sweeping changes the approval's status, and a reminder
     * changes nothing.
     */
    @Test
    void reminder_losingTheClaimRace_sendsNothing() {
        when(audience.resolve(any())).thenReturn(Mono.just(
                new ApprovalAudience.Audience("role:APPROVER", List.of("zte-dpo"), true)));
        when(notifications.save(any()))
                .thenReturn(Mono.error(new org.springframework.dao.DuplicateKeyException("already claimed")));
        WebClient.Builder mustNotBeUsed = WebClient.builder()
                .exchangeFunction(req -> Mono.error(new AssertionError("a lost claim must not send")));

        StepVerifier.create(new ApprovalNotifier(audience, notifications, mustNotBeUsed,
                        "https://hooks.example/abc", 5000, "").remind(approval(), "0.5", 3600))
                .verifyComplete();
    }

    @Test
    void reminder_claimsThenSends_andSettlesTheSameRow() {
        when(audience.resolve(any())).thenReturn(Mono.just(
                new ApprovalAudience.Audience("role:APPROVER", List.of("zte-dpo"), true)));
        when(notifications.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        StepVerifier.create(notifier("https://hooks.example/abc", respondingWith(HttpStatus.OK))
                        .remind(approval(), "0.5", 1800)
                        .map(ApprovalNotification::status))
                .expectNext("SENT")
                .verifyComplete();

        ArgumentCaptor<ApprovalNotification> saved = ArgumentCaptor.forClass(ApprovalNotification.class);
        verify(notifications, times(2)).save(saved.capture());
        assertThat(saved.getAllValues().get(0).status()).isEqualTo("CLAIMED");
        assertThat(saved.getAllValues().get(0).stage()).isEqualTo("0.5");
        assertThat(saved.getAllValues().get(1).status()).isEqualTo("SENT");
    }

    /** A reminder is still a message leaving the perimeter: same omissions apply. */
    @Test
    void reminderPayload_saysHowLongIsLeft_andStillHidesTheArguments() {
        Map<String, Object> body = notifier("https://hooks.example/abc", WebClient.builder())
                .reminderBody(approval(),
                        new ApprovalAudience.Audience("role:APPROVER", List.of("zte-dpo"), true), 1800);

        assertThat((String) body.get("text")).startsWith("Reminder").contains("30 min left");
        assertThat(body.toString())
                .doesNotContain("rep@nordwind.example")
                .doesNotContain("Your contract expires");
    }

    @Test
    void timeoutIsBounded() {
        when(audience.resolve(any())).thenReturn(Mono.just(
                new ApprovalAudience.Audience("role:APPROVER", List.of("zte-dpo"), true)));
        when(notifications.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        WebClient.Builder hangs = WebClient.builder()
                .exchangeFunction(req -> Mono.never());

        ApprovalNotifier notifier = new ApprovalNotifier(audience, notifications, hangs,
                "https://hooks.example/abc", 100, "");

        StepVerifier.create(notifier.deliver(approval()).map(ApprovalNotification::status))
                .expectNext("FAILED")
                .verifyComplete();
    }
}
