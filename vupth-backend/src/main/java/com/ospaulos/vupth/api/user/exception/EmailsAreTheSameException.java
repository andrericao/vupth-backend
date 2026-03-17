package com.ospaulos.vupth.api.user.exception;

public class EmailsAreTheSameException extends RuntimeException {
    public EmailsAreTheSameException() {
        super(String.format("The emails are the same!"));
    }
}
