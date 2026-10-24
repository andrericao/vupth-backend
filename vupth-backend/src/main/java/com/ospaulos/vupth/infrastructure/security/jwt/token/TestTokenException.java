package com.ospaulos.vupth.infrastructure.security.jwt.token;

import org.springframework.http.HttpStatus;

/** Recusa do token de teste (token-de-teste-sandbox): desligado ou fora do sandbox ({@code TEST_TOKEN_DISABLED}), fora do escopo, etc. */
public class TestTokenException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public TestTokenException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }
}
