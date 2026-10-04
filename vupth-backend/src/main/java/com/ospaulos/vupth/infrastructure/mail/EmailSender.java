package com.ospaulos.vupth.infrastructure.mail;

/**
 * Envio de e-mail transacional (backlog #1 — "esqueci minha senha").
 *
 * <p>Abstrai o provedor (SMTP em produção, no-op em teste/staging), do mesmo jeito que
 * {@code PushSender} abstrai o Expo. Implementações devem ser <b>best-effort</b> no que for
 * auxiliar, mas o reset precisa saber se falhou — por isso o método pode lançar
 * {@link EmailDeliveryException} e quem chama decide (aqui: loga e segue, sem vazar ao usuário se
 * o e-mail existe).</p>
 */
public interface EmailSender {

    /**
     * Envia o código de reset de senha ao e-mail informado.
     *
     * @param toEmail destinatário
     * @param code    código de 6 dígitos em claro (só trafega aqui; no banco fica só o hash)
     */
    void sendPasswordResetCode(String toEmail, String code);

    /**
     * Envia um e-mail de texto simples (avisos do vinculo — DEC-19 historico-de-vinculo). Mesmo
     * transporte e mesma falha ({@link EmailDeliveryException}) do reset; quem chama decide se e
     * best-effort.
     */
    void send(String toEmail, String subject, String body);
}
