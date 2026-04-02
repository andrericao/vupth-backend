package com.ospaulos.vupth.domain.refreshToken.exception;

public class RefreshTokenInvalidException extends RuntimeException {
    public RefreshTokenInvalidException() {
        super("Invalid, expired or revoked Refresh Token!");
    }
}
