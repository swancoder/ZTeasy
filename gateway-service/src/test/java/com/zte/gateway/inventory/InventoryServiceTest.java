package com.zte.gateway.inventory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cloud.gateway.event.RefreshRoutesEvent;
import org.springframework.context.ApplicationEventPublisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link InventoryService}.
 */
@ExtendWith(MockitoExtension.class)
class InventoryServiceTest {

    @Mock InventoryRepository repository;
    @Mock HealthMetricRepository healthMetricRepository;
    @Mock AutoDiscoveryWorker autoDiscoveryWorker;
    @Mock ApplicationEventPublisher eventPublisher;

    InventoryService service;

    private InventoryService newService() {
        return new InventoryService(repository, healthMetricRepository, autoDiscoveryWorker, eventPublisher);
    }

    @Test
    void create_newName_savesPendingAndTriggersDiscovery() {
        service = newService();
        UUID id = UUID.randomUUID();
        InventoryEntry saved = new InventoryEntry(id, "agent-x", TargetType.MCP, "http://agent-x", null, null, InventoryStatus.PENDING, Instant.now());

        when(repository.existsByName("agent-x")).thenReturn(Mono.just(false));
        when(repository.save(any())).thenReturn(Mono.just(saved));
        when(autoDiscoveryWorker.discoverAndUpdateStatus(saved)).thenReturn(Mono.empty());

        StepVerifier.create(service.create("agent-x", TargetType.MCP, "http://agent-x", null, null))
                .expectNext(saved)
                .verifyComplete();

        ArgumentCaptor<InventoryEntry> captor = ArgumentCaptor.forClass(InventoryEntry.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().status()).isEqualTo(InventoryStatus.PENDING);
        verify(autoDiscoveryWorker).discoverAndUpdateStatus(saved);
        // ADR-017: routes must refresh immediately so a freshly onboarded REST
        // service is routable without waiting for the periodic scheduler.
        verify(eventPublisher).publishEvent(any(RefreshRoutesEvent.class));
    }

    @Test
    void create_duplicateName_errorsWithoutSaving() {
        service = newService();
        when(repository.existsByName("agent-x")).thenReturn(Mono.just(true));

        StepVerifier.create(service.create("agent-x", TargetType.MCP, "http://agent-x", null, null))
                .expectError(DuplicateServiceNameException.class)
                .verify();

        verify(repository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void update_alwaysResetsToPendingAndTriggersDiscovery() {
        service = newService();
        UUID id = UUID.randomUUID();
        InventoryEntry updated = new InventoryEntry(id, "service-a", TargetType.REST, "https://new-host",
                "https://new-host/openapi.json", "http://new-host:9081", InventoryStatus.PENDING, Instant.now());

        when(repository.existsByNameAndIdNot("service-a", id)).thenReturn(Mono.just(false));
        when(repository.updateFields(eq(id), eq("service-a"), eq("REST"), eq("https://new-host"),
                eq("https://new-host/openapi.json"), eq("http://new-host:9081"), eq("PENDING")))
                .thenReturn(Mono.empty());
        when(repository.findById(id)).thenReturn(Mono.just(updated));
        when(autoDiscoveryWorker.discoverAndUpdateStatus(updated)).thenReturn(Mono.empty());

        StepVerifier.create(service.update(id, "service-a", TargetType.REST, "https://new-host",
                        "https://new-host/openapi.json", "http://new-host:9081"))
                .expectNext(updated)
                .verifyComplete();

        verify(repository).updateFields(id, "service-a", "REST", "https://new-host",
                "https://new-host/openapi.json", "http://new-host:9081", "PENDING");
        verify(autoDiscoveryWorker).discoverAndUpdateStatus(updated);
        verify(eventPublisher).publishEvent(any(RefreshRoutesEvent.class));
    }

    @Test
    void update_duplicateName_errorsWithoutUpdating() {
        service = newService();
        UUID id = UUID.randomUUID();
        when(repository.existsByNameAndIdNot("service-b", id)).thenReturn(Mono.just(true));

        StepVerifier.create(service.update(id, "service-b", TargetType.REST, "https://new-host", null, null))
                .expectError(DuplicateServiceNameException.class)
                .verify();

        verify(repository, never()).updateFields(any(), any(), any(), any(), any(), any(), any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void update_unknownId_errorsWithServiceNotFound() {
        service = newService();
        UUID id = UUID.randomUUID();
        when(repository.existsByNameAndIdNot("service-a", id)).thenReturn(Mono.just(false));
        when(repository.updateFields(eq(id), eq("service-a"), eq("REST"), eq("https://new-host"),
                any(), any(), eq("PENDING"))).thenReturn(Mono.empty());
        when(repository.findById(id)).thenReturn(Mono.empty());

        StepVerifier.create(service.update(id, "service-a", TargetType.REST, "https://new-host", null, null))
                .expectError(ServiceNotFoundException.class)
                .verify();

        verify(autoDiscoveryWorker, never()).discoverAndUpdateStatus(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void delete_delegatesToRepository() {
        service = newService();
        UUID id = UUID.randomUUID();
        when(repository.deleteById(id)).thenReturn(Mono.empty());

        StepVerifier.create(service.delete(id)).verifyComplete();

        verify(repository).deleteById(id);
        verify(eventPublisher).publishEvent(any(RefreshRoutesEvent.class));
    }

    @Test
    void list_joinsHealthAndSchemaFlagByServiceId() {
        service = newService();
        UUID withHealthId = UUID.randomUUID();
        UUID withoutHealthId = UUID.randomUUID();

        InventoryEntry withHealth = new InventoryEntry(withHealthId, "service-a", TargetType.REST, "https://a", null, null, InventoryStatus.ACTIVE, Instant.now());
        InventoryEntry withoutHealth = new InventoryEntry(withoutHealthId, "agent-b", TargetType.MCP, "http://b", null, null, InventoryStatus.PENDING, Instant.now());
        Instant lastCall = Instant.now();
        HealthMetric health = new HealthMetric(UUID.randomUUID(), withHealthId, 42, "UP", lastCall, Instant.now());

        when(repository.findAll()).thenReturn(Flux.just(withHealth, withoutHealth));
        when(healthMetricRepository.findByServiceIdIn(any())).thenReturn(Flux.just(health));
        // Only withHealthId has a captured schema — withoutHealthId does not, even though
        // list() is asked about both (ADR-016 amendment: status alone can't answer this).
        when(repository.findIdsWithDiscoveredSchema()).thenReturn(Flux.just(withHealthId));

        StepVerifier.create(service.list())
                .assertNext(views -> {
                    assertThat(views).hasSize(2);
                    var viewWithHealth = views.stream().filter(v -> v.id().equals(withHealthId)).findFirst().orElseThrow();
                    var viewWithoutHealth = views.stream().filter(v -> v.id().equals(withoutHealthId)).findFirst().orElseThrow();

                    assertThat(viewWithHealth.lastPingMs()).isEqualTo(42);
                    assertThat(viewWithHealth.actuatorStatus()).isEqualTo("UP");
                    assertThat(viewWithHealth.lastSuccessfulCall()).isEqualTo(lastCall);
                    assertThat(viewWithHealth.hasSchema()).isTrue();

                    assertThat(viewWithoutHealth.lastPingMs()).isNull();
                    assertThat(viewWithoutHealth.actuatorStatus()).isNull();
                    assertThat(viewWithoutHealth.lastSuccessfulCall()).isNull();
                    assertThat(viewWithoutHealth.hasSchema()).isFalse();
                })
                .verifyComplete();
    }

    @Test
    void fetchSchemaNow_unknownId_errorsWithoutCallingWorker() {
        service = newService();
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Mono.empty());

        StepVerifier.create(service.fetchSchemaNow(id))
                .expectError(ServiceNotFoundException.class)
                .verify();

        verify(autoDiscoveryWorker, never()).fetchSchemaNow(any());
    }

    @Test
    void fetchSchemaNow_knownId_delegatesToWorker() {
        service = newService();
        UUID id = UUID.randomUUID();
        InventoryEntry entry = new InventoryEntry(id, "service-a", TargetType.REST, "https://a", null, null, InventoryStatus.ACTIVE, Instant.now());
        when(repository.findById(id)).thenReturn(Mono.just(entry));
        when(autoDiscoveryWorker.fetchSchemaNow(entry)).thenReturn(Mono.empty());

        StepVerifier.create(service.fetchSchemaNow(id)).verifyComplete();

        verify(autoDiscoveryWorker).fetchSchemaNow(entry);
    }
}
