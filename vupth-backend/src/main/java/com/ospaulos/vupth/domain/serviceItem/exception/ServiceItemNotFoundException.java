package com.ospaulos.vupth.domain.serviceItem.exception;

import java.util.UUID;

public class ServiceItemNotFoundException extends RuntimeException {
    public ServiceItemNotFoundException(UUID id) {
        super("Service item not found with id: " + id);
    }
    public ServiceItemNotFoundException() {
        super("No service items found");
    }
}
