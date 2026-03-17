package com.ospaulos.vupth.domain.refreshToken.exception;

public class RefreshTokenNotFound extends RuntimeException {
    public RefreshTokenNotFound() {
        super("Refresh Token not found!");
    }
}
