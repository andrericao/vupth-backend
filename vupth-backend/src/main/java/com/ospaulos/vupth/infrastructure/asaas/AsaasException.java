package com.ospaulos.vupth.infrastructure.asaas;

import org.springframework.http.HttpStatus;

/** Falha ao falar com o Asaas (sandbox desligado, resposta de erro ou fora do ar). */
public class AsaasException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public AsaasException(HttpStatus status, String code, String message) {
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
