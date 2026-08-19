package com.zte.gateway.policy.def;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Schema-conformance check for the documentation example (ADR-011 Task 1 AC:
 * "at least one example YAML file is provided demonstrating each of the
 * policy categories" — extended from three to four categories by ADR-019's
 * {@code agentMcpToolHolds}). Loads {@code docs/examples/zte-policies-example.yaml}
 * with the real loader/validator so the doc can't silently drift out of sync
 * with the schema it's meant to illustrate.
 */
class DocumentationExampleConformanceTest {

    @Test
    void docsExample_loadsAndValidatesCleanly() {
        var loader = new YamlPolicyFileLoader();
        var validator = new PolicyValidator();

        PolicyDocument doc = loader.load(new FileSystemResource("../docs/examples/zte-policies-example.yaml"));
        PolicyValidationResult result = validator.validate(doc);

        assertThat(result.isValid())
                .as("docs/examples/zte-policies-example.yaml failed validation: %s", result.errors())
                .isTrue();
        assertThat(doc.users2service()).isNotEmpty();
        assertThat(doc.service2service()).isNotEmpty();
        assertThat(doc.agentMcpToolCalls()).isNotEmpty();
        assertThat(doc.agentMcpToolHolds()).isNotEmpty();
    }
}
