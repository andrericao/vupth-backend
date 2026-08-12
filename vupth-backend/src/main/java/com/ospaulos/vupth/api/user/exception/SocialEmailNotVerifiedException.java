package com.ospaulos.vupth.api.user.exception;

/**
 * E-mail nao verificado no provedor. Barra aqui porque o vinculo com conta
 * existente e feito POR e-mail: sem a verificacao, quem controlasse um e-mail
 * nao provado assumiria a conta de outra pessoa.
 */
public class SocialEmailNotVerifiedException extends RuntimeException {
    public SocialEmailNotVerifiedException(String email) {
        super(String.format("Email '%s' is not verified by the provider", email));
    }
}
