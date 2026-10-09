package com.ospaulos.vupth.api.user.exception;

/** Desativar a conta com compromisso vivo com terceiros (fila, atendimento, loja com fila) — 409. */
public class AccountDeactivateNotAllowedException extends RuntimeException {

    public AccountDeactivateNotAllowedException(String reason) {
        super(reason);
    }
}
