package com.ospaulos.vupth.api.user.exception;

/** Conta desativada em "so consultar": le, mas nao opera (fila, pedidos, loja) — 403 ACCOUNT_DEACTIVATED. */
public class AccountDeactivatedException extends RuntimeException {

    public AccountDeactivatedException() {
        super("Your account is deactivated. Reactivate it to use this feature.");
    }
}
