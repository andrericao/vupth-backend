package com.ospaulos.vupth.domain.establishment.exception;

/** Dono com loja nao apaga a conta: passa a posse ou apaga a loja antes (desativar-conta, regra (d)) — 409. */
public class OwnerMustTransferException extends RuntimeException {

    public OwnerMustTransferException() {
        super("Você ainda é dono de uma loja. Apague a loja ou passe a posse para um profissional antes de excluir a sua conta.");
    }
}
