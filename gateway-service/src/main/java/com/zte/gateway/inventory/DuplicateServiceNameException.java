package com.zte.gateway.inventory;

/** Thrown by {@link InventoryService#create} when {@code name} is already registered. */
public class DuplicateServiceNameException extends RuntimeException {
    public DuplicateServiceNameException(String name) {
        super("A service named '" + name + "' is already registered");
    }
}
