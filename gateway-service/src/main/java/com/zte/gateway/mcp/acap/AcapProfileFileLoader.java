package com.zte.gateway.mcp.acap;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Loads every file matching {@code zte.acap.profiles-location} into {@link
 * AcapProfile}s, keyed by {@code agentId} (Stage 3, ADR-020).
 *
 * <p>Deliberately best-effort, unlike {@link com.zte.gateway.policy.def.YamlPolicyFileLoader}'s
 * fail-fast contract: a malformed or duplicate profile is logged and
 * skipped, not a startup-failing exception — an ACAP profile is an
 * <em>additive</em> enrichment (ADR-019's coarse {@code agentMcpToolCalls}/
 * {@code agentMcpToolHolds} layer is always still enforced regardless), so
 * one bad file degrades only that one agent to coarse-only enforcement
 * rather than blocking the whole gateway from starting. A location
 * resolving to zero files is normal, not an error — ACAP profiles are
 * opt-in per agent.
 */
@Component
public class AcapProfileFileLoader {

    private static final Logger log = LoggerFactory.getLogger(AcapProfileFileLoader.class);

    private final YAMLMapper mapper = YAMLMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();
    private final ResourcePatternResolver resolver;

    public AcapProfileFileLoader(ResourcePatternResolver resolver) {
        this.resolver = resolver;
    }

    public Map<String, AcapProfile> loadAll(String locationPattern) {
        Resource[] resources;
        try {
            resources = resolver.getResources(locationPattern);
        } catch (IOException e) {
            log.warn("Could not resolve ACAP profile location '{}': {} — no ACAP profiles loaded",
                    locationPattern, e.getMessage());
            return Map.of();
        }

        Map<String, AcapProfile> profiles = new LinkedHashMap<>();
        for (Resource resource : resources) {
            loadOne(resource).ifPresent(profile -> {
                if (profiles.containsKey(profile.agentId())) {
                    log.warn("Duplicate ACAP profile for agent '{}' ({}) — keeping the first one loaded",
                            profile.agentId(), describe(resource));
                    return;
                }
                profiles.put(profile.agentId(), profile);
            });
        }
        return Map.copyOf(profiles);
    }

    private Optional<AcapProfile> loadOne(Resource resource) {
        try (InputStream in = resource.getInputStream()) {
            AcapProfile profile = mapper.readValue(in, AcapProfile.class);
            if (profile == null || profile.agentId() == null || profile.agentId().isBlank()) {
                log.error("ACAP profile {} is missing required 'agentId' — skipped", describe(resource));
                return Optional.empty();
            }
            if (profile.territory() == null || profile.territory().isBlank()) {
                log.error("ACAP profile for agent '{}' ({}) is missing required 'territory' — skipped",
                        profile.agentId(), describe(resource));
                return Optional.empty();
            }
            return Optional.of(profile);
        } catch (Exception e) {
            log.error("Failed to load ACAP profile from {} — skipped; that agent falls back to coarse-only "
                    + "policy enforcement (agentMcpToolCalls/agentMcpToolHolds only, no argument/field checks)",
                    describe(resource), e);
            return Optional.empty();
        }
    }

    private String describe(Resource resource) {
        try {
            return resource.getDescription();
        } catch (Exception e) {
            return resource.toString();
        }
    }
}
