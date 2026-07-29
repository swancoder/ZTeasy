package com.zte.gateway.mcp.mask;

import com.zte.gateway.mcp.model.JsonRpcResponse;
import org.springframework.stereotype.Component;

/**
 * Stub {@link DataMaskingFilter} — passes the response through unchanged.
 *
 * <p>Replace with real masking logic (emails, phone numbers, tokens, etc. in
 * {@code result.content}) once PII masking rules are defined. Kept as its own
 * class (rather than inlined in the handler) so the swap is a single bean
 * replacement.
 */
@Component
public class NoOpDataMaskingFilter implements DataMaskingFilter {

    @Override
    public JsonRpcResponse mask(JsonRpcResponse response) {
        // TODO: PII masking implementation goes here.
        return response;
    }
}
