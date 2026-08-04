package com.zte.gateway.policy.def;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link YamlPolicyFileLoader} against fixtures in
 * {@code src/test/resources/policy-fixtures/}.
 */
class YamlPolicyFileLoaderTest {

    private final YamlPolicyFileLoader loader = new YamlPolicyFileLoader();

    @Test
    void validFile_parsesIntoPolicyDocument() {
        PolicyDocument doc = loader.load(resource("valid.yaml"));

        assertThat(doc.schemaVersion()).isEqualTo(1);
        assertThat(doc.users2service()).hasSize(1);
        assertThat(doc.users2service().get(0).id()).isEqualTo("u1");
        assertThat(doc.agentMcpToolCalls()).hasSize(1);
    }

    @Test
    void missingFile_throwsPolicyLoadException() {
        Resource missing = new ClassPathResource("policy-fixtures/does-not-exist.yaml");

        assertThatThrownBy(() -> loader.load(missing))
                .isInstanceOf(PolicyLoadException.class)
                .hasMessageContaining("not found");
    }

    @Test
    void malformedYamlSyntax_throwsPolicyLoadExceptionWithLocation() {
        assertThatThrownBy(() -> loader.load(resource("malformed.yaml")))
                .isInstanceOf(PolicyLoadException.class);
    }

    @Test
    void unknownTopLevelKey_isRejectedRatherThanIgnored() {
        assertThatThrownBy(() -> loader.load(resource("unknown-key.yaml")))
                .isInstanceOf(PolicyLoadException.class);
    }

    private Resource resource(String name) {
        return new ClassPathResource("policy-fixtures/" + name);
    }
}
