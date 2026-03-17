package com.ospaulos.vupth.domain.client.exception;

import java.util.UUID;

public class ClientNotFoundException extends RuntimeException {
    public ClientNotFoundException(UUID id) {
        super("Client not found with id: " + id);
    }
}
