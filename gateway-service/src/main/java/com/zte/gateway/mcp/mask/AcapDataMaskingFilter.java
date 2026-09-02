package com.zte.gateway.mcp.mask;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.zte.gateway.mcp.acap.AcapProfile;
import com.zte.gateway.mcp.acap.AcapProfileStore;
import com.zte.gateway.mcp.model.JsonRpcResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The real {@link DataMaskingFilter} (Stage 32, ADR-032), replacing the
 * long-standing pass-through stub: field values the calling agent's ACAP
 * profile does not permit are replaced with a visible marker in the backend's
 * response — the request-side scope check (ADR-020) finally has a
 * response-side counterpart, so a backend that ignores the requested field
 * projection can no longer leak data past the gate.
 *
 * <p><strong>Deliberately structure-aware, not magical:</strong> masking
 * applies inside {@code "properties"} objects (the HubSpot search-response
 * shape this deployment actually fronts). A response that doesn't parse as
 * JSON, or contains no such objects, passes through UNCHANGED with a log
 * line saying so — a masking filter that guesses at unknown structures
 * breaks legal responses, which is worse than not masking (the request-side
 * check still applied). The marker is visible rather than the field being
 * dropped, so agent and auditor both see THAT something was withheld.
 *
 * <p>No profile, no read-resource mapping, or an error result: pass-through.
 */
@Component
public class AcapDataMaskingFilter implements DataMaskingFilter {

    private static final Logger log = LoggerFactory.getLogger("ZTE-MASKING");
    private static final String MARKER = "███ masked by ZTeasy";
    private static final String READ_PREFIX = "read_";

    private final AcapProfileStore profileStore;
    private final ObjectMapper objectMapper;

    public AcapDataMaskingFilter(AcapProfileStore profileStore, ObjectMapper objectMapper) {
        this.profileStore = profileStore;
        this.objectMapper = objectMapper;
    }

    /**
     * @param acapKeys profile lookup order for this caller (ADR-039): one key for an
     *                 agent, username-then-roles for a person. Masking that looked up
     *                 by a single id would silently stop masking the moment the caller
     *                 was a human governed by a role profile — the response would come
     *                 back complete, and nothing would say so.
     */
    @Override
    public JsonRpcResponse mask(java.util.List<String> acapKeys, String toolName, JsonRpcResponse response) {
        String agentId = acapKeys == null || acapKeys.isEmpty() ? null : acapKeys.get(0);
        if (agentId == null || toolName == null || !toolName.startsWith(READ_PREFIX)
                || response == null || response.result() == null
                || Boolean.TRUE.equals(response.result().get("isError"))) {
            return response;
        }
        return maskWithProfile(profileStore.find(acapKeys), agentId, toolName, response);
    }

    @Override
    public JsonRpcResponse mask(String agentId, String toolName, JsonRpcResponse response) {
        if (agentId == null || toolName == null || !toolName.startsWith(READ_PREFIX)
                || response.result() == null || Boolean.TRUE.equals(response.result().get("isError"))) {
            return response;
        }
        return maskWithProfile(profileStore.find(agentId), agentId, toolName, response);
    }

    private JsonRpcResponse maskWithProfile(Optional<AcapProfile> profile, String agentId, String toolName,
                                             JsonRpcResponse response) {
        if (profile.isEmpty()) {
            return response;
        }
        String resource = toolName.substring(READ_PREFIX.length());
        Set<String> allowed = profile.get().readGrants().stream()
                .filter(g -> resource.equals(g.resource()))
                .flatMap(g -> g.fields() == null ? java.util.stream.Stream.<String>empty() : g.fields().stream())
                .collect(Collectors.toSet());
        if (allowed.isEmpty()) {
            // No field list means the grant (if any) was field-unscoped — the
            // request-side check governs; nothing to mask against.
            return response;
        }

        Object contentObj = response.result().get("content");
        if (!(contentObj instanceof List<?> content)) {
            return response;
        }
        int masked = 0;
        List<Object> newContent = new ArrayList<>();
        for (Object block : content) {
            if (block instanceof Map<?, ?> m && "text".equals(m.get("type")) && m.get("text") instanceof String text) {
                MaskResult r = maskText(text, allowed);
                masked += r.maskedCount();
                Map<String, Object> newBlock = new HashMap<>();
                m.forEach((k, v) -> newBlock.put(String.valueOf(k), v));
                newBlock.put("text", r.text());
                newContent.add(newBlock);
            } else {
                newContent.add(block);
            }
        }
        if (masked == 0) {
            return response;
        }
        log.info("[ZTE-MASKING] masked {} field value(s) in '{}' response for agent '{}' (allowed for '{}': {})",
                masked, toolName, agentId, resource, allowed);
        Map<String, Object> newResult = new HashMap<>(response.result());
        newResult.put("content", newContent);
        return new JsonRpcResponse(response.jsonrpc(), response.id(), newResult, response.error());
    }

    /** Parses the block's text as JSON and masks inside every "properties" object; unparseable text passes through. */
    private MaskResult maskText(String text, Set<String> allowed) {
        JsonNode root;
        try {
            root = objectMapper.readTree(text);
        } catch (Exception e) {
            log.info("[ZTE-MASKING] response text is not JSON — passed through unmasked");
            return new MaskResult(text, 0);
        }
        int masked = maskNode(root, allowed);
        if (masked == 0) {
            return new MaskResult(text, 0);
        }
        try {
            return new MaskResult(objectMapper.writeValueAsString(root), masked);
        } catch (Exception e) {
            return new MaskResult(text, 0);
        }
    }

    private int maskNode(JsonNode node, Set<String> allowed) {
        int masked = 0;
        if (node instanceof ObjectNode obj) {
            Iterator<Map.Entry<String, JsonNode>> fields = obj.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                if ("properties".equals(entry.getKey()) && entry.getValue() instanceof ObjectNode props) {
                    Iterator<String> names = props.fieldNames();
                    List<String> disallowed = new ArrayList<>();
                    while (names.hasNext()) {
                        String name = names.next();
                        if (!allowed.contains(name)) {
                            disallowed.add(name);
                        }
                    }
                    for (String name : disallowed) {
                        props.set(name, TextNode.valueOf(MARKER));
                    }
                    masked += disallowed.size();
                } else {
                    masked += maskNode(entry.getValue(), allowed);
                }
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                masked += maskNode(child, allowed);
            }
        }
        return masked;
    }

    private record MaskResult(String text, int maskedCount) {}
}
