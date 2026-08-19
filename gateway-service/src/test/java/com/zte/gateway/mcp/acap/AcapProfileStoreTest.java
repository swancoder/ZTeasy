package com.zte.gateway.mcp.acap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.test.StepVerifier;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/** Unit tests for {@link AcapProfileStore}. */
@ExtendWith(MockitoExtension.class)
class AcapProfileStoreTest {

    @Mock AcapProfileFileLoader loader;

    private static final AcapProfile PROFILE_A = new AcapProfile("agent-a", "EMEA", new AcapScope(java.util.List.of(), false));
    private static final AcapProfile PROFILE_B = new AcapProfile("agent-b", "NA", new AcapScope(java.util.List.of(), true));

    private AcapProfileStore newStore() {
        AcapProfileProperties properties = new AcapProfileProperties();
        return new AcapProfileStore(loader, properties);
    }

    @Test
    void constructor_loadsProfilesAtStartup() {
        when(loader.loadAll("classpath:acap-profiles/*.yaml")).thenReturn(Map.of("agent-a", PROFILE_A));

        AcapProfileStore store = newStore();

        assertThat(store.find("agent-a")).contains(PROFILE_A);
        assertThat(store.find("agent-b")).isEmpty();
    }

    @Test
    void find_unknownAgent_isEmpty() {
        when(loader.loadAll("classpath:acap-profiles/*.yaml")).thenReturn(Map.of());

        AcapProfileStore store = newStore();

        assertThat(store.find("nobody")).isEqualTo(Optional.empty());
    }

    @Test
    void all_returnsEveryLoadedProfile() {
        when(loader.loadAll("classpath:acap-profiles/*.yaml")).thenReturn(Map.of("agent-a", PROFILE_A, "agent-b", PROFILE_B));

        AcapProfileStore store = newStore();

        assertThat(store.all()).containsExactlyInAnyOrder(PROFILE_A, PROFILE_B);
    }

    @Test
    void reload_swapsToNewlyLoadedSet() {
        when(loader.loadAll("classpath:acap-profiles/*.yaml"))
                .thenReturn(Map.of("agent-a", PROFILE_A))
                .thenReturn(Map.of("agent-b", PROFILE_B));

        AcapProfileStore store = newStore();
        assertThat(store.find("agent-a")).isPresent();

        StepVerifier.create(store.reload())
                .assertNext(result -> assertThat(result.loadedCount()).isEqualTo(1))
                .verifyComplete();

        assertThat(store.find("agent-a")).isEmpty();
        assertThat(store.find("agent-b")).isPresent();
    }
}
