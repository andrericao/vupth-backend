package com.ospaulos.vupth.api.user.exception;

/** Link de exclusao vencido, ja usado, morto por senhas erradas ou que nao existe — 410. */
public class AccountDeletionLinkInvalidException extends RuntimeException {

    public AccountDeletionLinkInvalidException() {
        super("This deletion link has expired or was already used. Request a new one.");
    }
}
