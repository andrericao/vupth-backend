package com.ospaulos.vupth.infrastructure.mail;

/**
 * Falha ao entregar um e-mail ao provedor. Quem chama decide se propaga ou engole — no fluxo de
 * reset de senha a falha é logada e engolida (não vazamos ao usuário se o e-mail existe).
 */
public class EmailDeliveryException extends RuntimeException {
    public EmailDeliveryException(String message, Throwable cause) {
        super(message, cause);
    }
}
