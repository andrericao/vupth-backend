package com.ospaulos.vupth.domain.payment.exception;

/**
 * virada-do-gateway (DEC-163/164): com o recebimento pelo app valendo (Asaas real, passados os 7 dias do aviso), o profissional sem
 * CPF nao consegue abrir (entrar em expediente / abrir a loja). Vira 422 ACCOUNT_INCOMPLETE; o Partner leva ele as Configuracoes.
 * Nunca derruba atendimento em curso: so a abertura.
 */
public class AccountIncompleteException extends RuntimeException {
    public AccountIncompleteException() {
        super("Conta incompleta: informe o CPF nas Configuracoes para continuar atendendo.");
    }
}
