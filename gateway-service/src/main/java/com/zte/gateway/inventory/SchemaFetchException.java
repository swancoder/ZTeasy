package com.zte.gateway.inventory;

/**
 * Thrown by {@link AutoDiscoveryWorker#fetchSchemaNow} when a synchronous,
 * UI-triggered discovery doesn't produce a usable schema — the target was
 * unreachable, timed out, returned a non-2xx status, or returned a 2xx with
 * no valid JSON body. The message is written to be shown directly to an
 * operator (the Admin Console's "Fetch" Snackbar), not just logged.
 */
public class SchemaFetchException extends RuntimeException {
    public SchemaFetchException(String message) {
        super(message);
    }

    public SchemaFetchException(String message, Throwable cause) {
        super(message, cause);
    }
}
