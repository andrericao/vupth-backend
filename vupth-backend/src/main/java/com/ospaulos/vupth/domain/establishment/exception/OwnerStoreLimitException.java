package com.ospaulos.vupth.domain.establishment.exception;

/** O dono ja tem o maximo de lojas (multi-loja-do-dono, 17/10: 5). Vira {@code 409 OWNER_STORE_LIMIT}. */
public class OwnerStoreLimitException extends RuntimeException {

    private final int limit;

    public OwnerStoreLimitException(int limit) {
        super("Voce ja tem o maximo de " + limit + " lojas.");
        this.limit = limit;
    }

    public int getLimit() {
        return limit;
    }
}
