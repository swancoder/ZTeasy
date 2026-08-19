package com.zte.gateway.mcp.acap;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** {@code zte.acap.*} configuration (Stage 3, ADR-020). */
@Component
@ConfigurationProperties(prefix = "zte.acap")
public class AcapProfileProperties {

    /**
     * Spring resource-pattern location for per-agent profile files — {@code
     * classpath:}/{@code file:}, may contain {@code *}/{@code **} wildcards
     * (resolved via {@link org.springframework.core.io.support.ResourcePatternResolver}).
     * Unlike {@code zte.policy.file}, a location that resolves to zero files
     * is not an error — ACAP profiles are optional per agent.
     */
    private String profilesLocation = "classpath:acap-profiles/*.yaml";

    public String getProfilesLocation() {
        return profilesLocation;
    }

    public void setProfilesLocation(String profilesLocation) {
        this.profilesLocation = profilesLocation;
    }
}
