package com.ospaulos.vupth.domain.serviceItem.exception;

public class ServiceItemAlreadyExistsException extends RuntimeException {
    public ServiceItemAlreadyExistsException(String name) {
        super("Service item '" + name + "' already exists in this establishment");
    }
}
