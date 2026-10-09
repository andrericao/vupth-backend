package com.ospaulos.vupth.domain.establishment.exception;

/** Dono com loja nao apaga a conta: passa a posse ou apaga a loja antes (desativar-conta, regra (d)) — 409. */
public class OwnerMustTransferException extends RuntimeException {

    public OwnerMustTransferException() {
        super("You still own an establishment. Transfer it to a professional or delete it before deleting your account.");
    }
}
