package com.zte.gateway.inventory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
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

    InventoryService service;

    private InventoryService newService() {
        return new InventoryService(repository, healthMetricRepository, autoDiscoveryWorker);
    }

    @Test
    void create_newName_savesPendingAndTriggersDiscovery() {
        service = newService();
        UUID id = UUID.randomUUID();
        InventoryEntry saved = new InventoryEntry(id, "agent-x", TargetType.MCP, "http://agent-x", null, InventoryStatus.PENDING, Instant.now());

        when(repository.existsByName("agent-x")).thenReturn(Mono.just(false));
        when(repository.save(any())).thenReturn(Mono.just(saved));
        when(autoDiscoveryWorker.discoverAndUpdateStatus(saved)).thenReturn(Mono.empty());

        StepVerifier.create(service.create("agent-x", TargetType.MCP, "http://agent-x", null))
                .expectNext(saved)
                .verifyComplete();

        ArgumentCaptor<InventoryEntry> captor = ArgumentCaptor.forClass(InventoryEntry.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().status()).isEqualTo(InventoryStatus.PENDING);
        verify(autoDiscoveryWorker).discoverAndUpdateStatus(saved);
    }

    @Test
    void create_duplicateName_errorsWithoutSaving() {
        service = newService();
        when(repository.existsByName("agent-x")).thenReturn(Mono.just(true));

        StepVerifier.create(service.create("agent-x", TargetType.MCP, "http://agent-x", null))
                .expectError(DuplicateServiceNameException.class)
                .verify();

        verify(repository, never()).save(any());
    }

    @Test
    void update_alwaysResetsToPendingAndTriggersDiscovery() {
        service = newService();
        UUID id = UUID.randomUUID();
        InventoryEntry updated = new InventoryEntry(id, "service-a", TargetType.REST, "https://new-host", "http://new-host:9081", InventoryStatus.PENDING, Instant.now());

        when(repository.updateFields(eq(id), eq("service-a"), eq("REST"), eq("https://new-host"), eq("http://new-host:9081"), eq("PENDING")))
                .thenReturn(Mono.empty());
        when(repository.findById(id)).thenReturn(Mono.just(updated));
        when(autoDiscoveryWorker.discoverAndUpdateStatus(updated)).thenReturn(Mono.empty());

        StepVerifier.create(service.update(id, "service-a", TargetType.REST, "https://new-host", "http://new-host:9081"))
                .expectNext(updated)
                .verifyComplete();

        verify(repository).updateFields(id, "service-a", "REST", "https://new-host", "http://new-host:9081", "PENDING");
        verify(autoDiscoveryWorker).discoverAndUpdateStatus(updated);
    }

    @Test
    void delete_delegatesToRepository() {
        service = newService();
        UUID id = UUID.randomUUID();
        when(repository.deleteById(id)).thenReturn(Mono.empty());

        StepVerifier.create(service.delete(id)).verifyComplete();

        verify(repository).deleteById(id);
    }

    @Test
    void list_joinsHealthByServiceIdWithNullsForMissingHealth() {
        service = newService();
        UUID withHealthId = UUID.randomUUID();
        UUID withoutHealthId = UUID.randomUUID();

        InventoryEntry withHealth = new InventoryEntry(withHealthId, "service-a", TargetType.REST, "https://a", null, InventoryStatus.ACTIVE, Instant.now());
        InventoryEntry withoutHealth = new InventoryEntry(withoutHealthId, "agent-b", TargetType.MCP, "http://b", null, InventoryStatus.PENDING, Instant.now());
        Instant lastCall = Instant.now();
        HealthMetric health = new HealthMetric(UUID.randomUUID(), withHealthId, 42, "UP", lastCall, Instant.now());

        when(repository.findAll()).thenReturn(Flux.just(withHealth, withoutHealth));
        when(healthMetricRepository.findByServiceIdIn(any())).thenReturn(Flux.just(health));

        StepVerifier.create(service.list())
                .assertNext(views -> {
                    assertThat(views).hasSize(2);
                    var viewWithHealth = views.stream().filter(v -> v.id().equals(withHealthId)).findFirst().orElseThrow();
                    var viewWithoutHealth = views.stream().filter(v -> v.id().equals(withoutHealthId)).findFirst().orElseThrow();

                    assertThat(viewWithHealth.lastPingMs()).isEqualTo(42);
                    assertThat(viewWithHealth.actuatorStatus()).isEqualTo("UP");
                    assertThat(viewWithHealth.lastSuccessfulCall()).isEqualTo(lastCall);

                    assertThat(viewWithoutHealth.lastPingMs()).isNull();
                    assertThat(viewWithoutHealth.actuatorStatus()).isNull();
                    assertThat(viewWithoutHealth.lastSuccessfulCall()).isNull();
                })
                .verifyComplete();
    }
}
