package com.ospaulos.vupth.domain.user.exception;

/**
 * O e-mail nao tem conta (BAK-6 · D2, 16/09). Login e "esqueci minha senha" passam a dizer isso em
 * vez de responder igual para quem existe e quem nao existe — o app mostra "e-mail nao cadastrado"
 * e oferece o cadastro. O custo (dar a quem sonda o endpoint a lista de quem tem conta) e contido
 * pelo rate limit das rotas de autenticacao ({@code AuthRateLimitFilter}).
 */
public class EmailNotRegisteredException extends RuntimeException {

    public EmailNotRegisteredException() {
        super("E-mail not registered");
    }
}
