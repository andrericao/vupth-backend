package com.ospaulos.vupth.domain.user.exception;

/** 422 CPF_INVALID: os digitos do CPF nao fecham (cpf-do-profissional-opcional). A mensagem nunca traz o CPF. */
public class CpfInvalidException extends RuntimeException {
    public CpfInvalidException() {
        super("CPF invalido");
    }
}
