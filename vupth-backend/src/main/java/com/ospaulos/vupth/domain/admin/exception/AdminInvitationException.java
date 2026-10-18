package com.ospaulos.vupth.domain.admin.exception;

import org.springframework.http.HttpStatus;

/** Falha do convite ou da gestao dos usuarios do painel (convite-com-permissoes). */
public class AdminInvitationException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public AdminInvitationException(HttpStatus status, String code, String message) {
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
