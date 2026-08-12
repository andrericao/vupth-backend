package com.ospaulos.vupth.infrastructure.push;

/**
 * Recibo de UMA mensagem de push, por token (backlog #28).
 *
 * <p>A Expo entrega em duas etapas e cada uma tem seu recibo. O <b>ticket</b> e a resposta imediata
 * do {@code POST /push/send}: um por mensagem enviada, na mesma ordem do lote. Ele diz apenas que a
 * Expo <i>aceitou</i> a mensagem e vai repassa-la a APNs/FCM — <b>nao</b> que o aparelho recebeu.
 * A confirmacao de entrega e o <b>receipt</b>, consultado depois pelo {@code id} (ver a nota de
 * "segunda etapa" no {@link ExpoPushSender}).</p>
 *
 * <p>O que interessa aqui e o {@code status: "error"}: o codigo vem em {@code details.error} e o
 * mais importante e {@code DeviceNotRegistered} (app desinstalado ou token rotacionado), que e o
 * unico sinal que temos de que o token morreu — sem le-lo, ele fica na base para sempre.</p>
 *
 * @param token  o Expo Push Token ao qual este recibo se refere
 * @param ok     {@code true} quando a Expo aceitou a mensagem
 * @param error  codigo de {@code details.error} (ex.: {@code DeviceNotRegistered}); {@code null} se aceita
 * @param message mensagem legivel devolvida pela Expo; {@code null} se aceita
 */
public record PushTicket(String token, boolean ok, String error, String message) {

    /** Codigo da Expo para token morto — o aparelho desinstalou o app ou o token foi rotacionado. */
    public static final String DEVICE_NOT_REGISTERED = "DeviceNotRegistered";

    public static PushTicket accepted(String token) {
        return new PushTicket(token, true, null, null);
    }

    public static PushTicket rejected(String token, String error, String message) {
        return new PushTicket(token, false, error, message);
    }

    /** Este token deve sair da base: a Expo afirma que o aparelho nao recebe mais push. */
    public boolean isDeviceGone() {
        return !ok && DEVICE_NOT_REGISTERED.equals(error);
    }
}
