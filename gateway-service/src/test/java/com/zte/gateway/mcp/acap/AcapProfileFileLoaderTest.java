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

        assertThat(profiles).containsKey("crm-account-health-emea-01");
        AcapProfile profile = profiles.get("crm-account-health-emea-01");
        assertThat(profile.territory()).isEqualTo("EMEA");
        assertThat(profile.writeAllowed()).isFalse();
        assertThat(profile.readGrants()).hasSize(3);

        assertThat(profile.agent()).isNotNull();
        assertThat(profile.agent().name()).isEqualTo("Account-Health Assistant");
        assertThat(profile.agent().client()).isEqualTo("Nordwind Components");
        assertThat(profile.agent().owner().email()).isEqualTo("sales-ops@nordwind.example");
        assertThat(profile.agent().reauthDue()).isEqualTo("2026-02-01");

        assertThat(profile.risk()).isNotNull();
        assertThat(profile.risk().euAiActClass()).isEqualTo("limited");
        assertThat(profile.risk().internalTier()).isEqualTo(2);

        assertThat(profile.thresholds()).hasSize(1);
        AcapThreshold threshold = profile.thresholds().get(0);
        assertThat(threshold.metric()).isEqualTo("followup_drafts_per_day");
        assertThat(threshold.toolName()).isEqualTo("draft_followup");
        assertThat(threshold.limit()).isEqualTo(30);
        assertThat(threshold.onExceed()).isEqualTo("hold");
    }
}
