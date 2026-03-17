package com.ospaulos.vupth.api.user.exception;

import java.util.UUID;

public class UserNotDeletedException extends RuntimeException {
    public UserNotDeletedException(UUID id) {
        super(String.format("User %s not deleted!", id));
    }
}
