package com.ospaulos.vupth.domain.admin.exception;

import org.springframework.http.HttpStatus;

/** Falha do segundo fator do painel: codigo errado, ja ativo, nao configurado ou conta travada. */
public class AdminTotpException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public AdminTotpException(HttpStatus status, String code, String message) {
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
