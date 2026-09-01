package com.zte.gateway.mcp.mask;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zte.gateway.mcp.acap.AcapProfile;
import com.zte.gateway.mcp.acap.AcapProfileStore;
import com.zte.gateway.mcp.acap.AcapReadGrant;
import com.zte.gateway.mcp.acap.AcapScope;
import com.zte.gateway.mcp.model.JsonRpcResponse;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for response masking (Stage 32, ADR-032) — the response-side
 * counterpart to ADR-020's request-side field check. The rule that matters
 * most here is the negative one: an unfamiliar response shape must pass
 * through untouched rather than be mangled.
 */
class AcapDataMaskingFilterTest {

    private static final String MARKER = "███ masked by ZTeasy";

    private final ObjectMapper mapper = new ObjectMapper();

    private AcapDataMaskingFilter filterFor(AcapProfile profile) {
        AcapProfileStore store = Mockito.mock(AcapProfileStore.class);
        Mockito.lenient().when(store.find(Mockito.anyString()))
                .thenAnswer(inv -> profile != null && profile.agentId().equals(inv.getArgument(0))
                        ? Optional.of(profile) : Optional.empty());
        return new AcapDataMaskingFilter(store, mapper);
    }

    private static AcapProfile profile(String agentId, List<String> fields) {
        return new AcapProfile(agentId, "EMEA",
                new AcapScope(List.of(new AcapReadGrant("contacts", fields)), false));
    }

    private static JsonRpcResponse responseWith(String text) {
        return JsonRpcResponse.success(1, Map.of(
                "content", List.of(Map.of("type", "text", "text", text)),
                "isError", false));
    }

    private static String textOf(JsonRpcResponse response) {
        List<?> content = (List<?>) response.result().get("content");
        return (String) ((Map<?, ?>) content.get(0)).get("text");
    }

    @Test
    void disallowedProperty_isReplacedWithTheMarker_allowedOneSurvives() {
        AcapDataMaskingFilter filter = filterFor(profile("crm", List.of("name", "company")));
        String body = """
                {"results":[{"id":"1","properties":{"name":"Ada","company":"Nordwind","id_number":"AB-99"}}]}""";

        JsonRpcResponse masked = filter.mask("crm", "read_contacts", responseWith(body));

        String out = textOf(masked);
        assertThat(out).contains("\"name\":\"Ada\"").contains("\"company\":\"Nordwind\"");
        assertThat(out).doesNotContain("AB-99").contains(MARKER);
    }

    @Test
    void agentWithoutProfile_passesThroughUntouched() {
        AcapDataMaskingFilter filter = filterFor(profile("crm", List.of("name")));
        String body = """
                {"results":[{"properties":{"name":"Ada","id_number":"AB-99"}}]}""";

        JsonRpcResponse out = filter.mask("some-other-agent", "read_contacts", responseWith(body));

        assertThat(textOf(out)).contains("AB-99");
    }

    @Test
    void unknownStructure_passesThroughUnchangedRatherThanBeingMangled() {
        AcapDataMaskingFilter filter = filterFor(profile("crm", List.of("name")));
        String body = "a plain text answer from some other backend, id_number AB-99";

        JsonRpcResponse out = filter.mask("crm", "read_contacts", responseWith(body));

        assertThat(textOf(out)).isEqualTo(body);
    }

    @Test
    void nonReadTool_isNotMasked() {
        AcapDataMaskingFilter filter = filterFor(profile("crm", List.of("name")));
        String body = """
                {"results":[{"properties":{"id_number":"AB-99"}}]}""";

        JsonRpcResponse out = filter.mask("crm", "update_deal", responseWith(body));

        assertThat(textOf(out)).contains("AB-99");
    }

    @Test
    void errorResult_isNeverRewritten() {
        AcapDataMaskingFilter filter = filterFor(profile("crm", List.of("name")));
        JsonRpcResponse denied = JsonRpcResponse.success(1, Map.of(
                "content", List.of(Map.of("type", "text", "text", "{\"properties\":{\"id_number\":\"AB-99\"}}")),
                "isError", true));

        JsonRpcResponse out = filter.mask("crm", "read_contacts", denied);

        assertThat(textOf(out)).contains("AB-99");
    }

    @Test
    void nestedProperties_areMaskedAtEveryLevel() {
        AcapDataMaskingFilter filter = filterFor(profile("crm", List.of("name")));
        String body = """
                {"page":{"results":[{"properties":{"name":"Ada","secret":"s1"}},
                                    {"properties":{"name":"Bob","secret":"s2"}}]}}""";

        JsonRpcResponse masked = filter.mask("crm", "read_contacts", responseWith(body));

        String out = textOf(masked);
        assertThat(out).doesNotContain("s1").doesNotContain("s2");
        assertThat(out).contains("Ada").contains("Bob");
    }
}
