package com.ospaulos.vupth.infrastructure.mail;

/**
 * Texto do e-mail de reset de senha, em um lugar so.
 *
 * <p>Existe porque ha mais de um transporte para a MESMA mensagem ({@link SmtpEmailSender} e
 * {@link BrevoApiEmailSender}). Com o texto duplicado, uma correcao de redacao entraria em um
 * transporte e nao no outro — e o usuario receberia versoes diferentes dependendo de qual
 * {@code MAIL_PROVIDER} estivesse no ar, sem ninguem perceber (o e-mail so e lido fora daqui).</p>
 */
final class PasswordResetMessage {

    static final String SUBJECT = "VUPTH — código de recuperação de senha";

    private PasswordResetMessage() {
    }

    static String body(String code) {
        return "Seu código de recuperação de senha é: " + code + "\n\n"
                + "Ele expira em 15 minutos e só pode ser usado uma vez.\n"
                + "Se você não pediu a troca de senha, ignore este e-mail.";
    }
}
