package com.ospaulos.vupth.api.user.exception;

public class EmailNotAvailableException extends RuntimeException {
    public EmailNotAvailableException(String email) {
        super(String.format("Email '%s' is already registered", email));
    }
}
