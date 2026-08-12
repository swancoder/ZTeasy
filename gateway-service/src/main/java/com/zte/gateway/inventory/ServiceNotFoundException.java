package com.zte.gateway.inventory;

import java.util.UUID;

/** Thrown by {@link InventoryService#fetchSchemaNow} when {@code id} isn't registered. */
public class ServiceNotFoundException extends RuntimeException {
    public ServiceNotFoundException(UUID id) {
        super("No registered service with id '" + id + "'");
    }
}
