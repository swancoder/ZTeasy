package com.zte.gateway.mcp.acap;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link AcapProfileFileLoader} against fixtures in {@code
 * src/test/resources/acap-profile-fixtures/}. Uses a real {@link
 * PathMatchingResourcePatternResolver} (no I/O beyond reading these small
 * classpath fixtures) rather than mocking resource resolution.
 */
class AcapProfileFileLoaderTest {

    private final AcapProfileFileLoader loader = new AcapProfileFileLoader(new PathMatchingResourcePatternResolver());

    @Test
    void validFiles_loadedAndKeyedByAgentId() {
        Map<String, AcapProfile> profiles = loader.loadAll("classpath:acap-profile-fixtures/valid/*.yaml");

        assertThat(profiles).hasSize(2).containsKeys("agent-a", "agent-b");
        assertThat(profiles.get("agent-a").territory()).isEqualTo("EMEA");
        assertThat(profiles.get("agent-b").writeAllowed()).isTrue();
    }

    @Test
    void locationMatchingNothing_returnsEmptyMap_notAnError() {
        Map<String, AcapProfile> profiles = loader.loadAll("classpath:acap-profile-fixtures/does-not-exist/*.yaml");
        assertThat(profiles).isEmpty();
    }

    @Test
    void missingAgentId_skippedNotThrown() {
        Map<String, AcapProfile> profiles = loader.loadAll("classpath:acap-profile-fixtures/missing-agent-id/*.yaml");
        assertThat(profiles).isEmpty();
    }

    @Test
    void missingTerritory_skippedNotThrown() {
        Map<String, AcapProfile> profiles = loader.loadAll("classpath:acap-profile-fixtures/missing-territory/*.yaml");
        assertThat(profiles).isEmpty();
    }

    @Test
    void malformedYaml_skippedNotThrown() {
        Map<String, AcapProfile> profiles = loader.loadAll("classpath:acap-profile-fixtures/malformed/*.yaml");
        assertThat(profiles).isEmpty();
    }

    @Test
    void duplicateAgentId_onlyOneKept() {
        Map<String, AcapProfile> profiles = loader.loadAll("classpath:acap-profile-fixtures/duplicates/*.yaml");
        assertThat(profiles).hasSize(1).containsKey("dup-agent");
    }

    /**
     * Loads the real, shipped demo profile ({@code src/main/resources/acap-profiles/},
     * on the test classpath too) with the actual loader — a stale doc/reality
     * mismatch here would otherwise only surface at gateway startup. Mirrors
     * {@code DocumentationExampleConformanceTest}'s role for the main policy YAML.
     */
    @Test
    void realDemoProfile_loadsWithFullStage6Metadata() {
        Map<String, AcapProfile> profiles = loader.loadAll("classpath:acap-profiles/*.yaml");

        // Stage 42: the demo is the chat console alone, so the profile that ships is
        // the one governing PEOPLE. It carries the same shape the retired agent's did
        // — territory, field-scoped reads, a write ban, a daily threshold, lifecycle
        // metadata — which is the point: a person is governed like an agent was.
        assertThat(profiles).containsKey("role:CHAT_USER");
        AcapProfile profile = profiles.get("role:CHAT_USER");
        assertThat(profile.territory()).isEqualTo("EMEA");
        assertThat(profile.writeAllowed()).isFalse();
        assertThat(profile.readGrants()).hasSize(3);

        assertThat(profile.agent()).isNotNull();
        assertThat(profile.agent().name()).isEqualTo("Chat Console user");
        assertThat(profile.agent().owner().email()).isEqualTo("sales-ops@nordwind.example");
        assertThat(profile.risk()).isNotNull();
        assertThat(profile.thresholds()).hasSize(1);
        assertThat(profile.thresholds().get(0).toolName()).isEqualTo("draft_followup");
    }
}
