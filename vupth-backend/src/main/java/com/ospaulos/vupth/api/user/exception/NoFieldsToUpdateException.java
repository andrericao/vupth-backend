package com.ospaulos.vupth.api.user.exception;

public class NoFieldsToUpdateException extends RuntimeException {
    public NoFieldsToUpdateException() {
        super("Request body must contain at least one valid field to update.");
    }
}