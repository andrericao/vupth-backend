package com.ospaulos.vupth.infrastructure.push;

/**
 * O recibo de um ticket (segunda etapa da Expo, recibos-da-expo). {@code ok} quer dizer que a Expo
 * entregou a mensagem a FCM/APNs; o aparelho nao confirma nada alem disso. Em erro, o codigo vem em
 * {@code error} — {@code DeviceNotRegistered} e o token morto.
 *
 * @param ticketId o id do ticket devolvido no envio
 * @param ok       {@code true} quando a Expo repassou a FCM/APNs
 * @param error    o {@code details.error} da Expo; {@code null} se ok
 * @param message  a mensagem legivel da Expo; {@code null} se ok
 */
public record PushReceipt(String ticketId, boolean ok, String error, String message) {

    /** O token deste recibo morreu: o app saiu do aparelho ou o token trocou. */
    public boolean isDeviceGone() {
        return !ok && PushTicket.DEVICE_NOT_REGISTERED.equals(error);
    }
}
