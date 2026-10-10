package com.ospaulos.vupth.infrastructure.push;

import java.util.List;
import java.util.Map;

/**
 * Envio de notificacoes push para uma lista de device tokens (backlog #1).
 *
 * <p>Abstrai o provedor (Expo em producao, no-op em teste/staging). Implementacoes devem ser
 * <b>best-effort</b>: nunca lancar — falha de rede/provedor e logada e engolida, pois o push
 * e um canal auxiliar e nao pode quebrar a operacao principal.</p>
 *
 * <p><b>Backlog #28:</b> o retorno passou de {@code void} para a lista de {@link PushTicket} —
 * um recibo por token. Sem ele o chamador nao tem como saber que um token morreu
 * ({@code DeviceNotRegistered}) nem que o lote inteiro falhou, e o log dizia "enviou" em qualquer
 * cenario. Quem decide o que fazer com um token morto e o {@link PushNotificationPublisher}; o
 * sender e transporte puro e nao toca em persistencia.</p>
 *
 * @return um ticket por token efetivamente enviado — <b>nunca {@code null}</b>; lista vazia quando
 *         nao havia o que enviar ou quando a resposta do provedor foi ilegivel
 */
public interface PushSender {

    List<PushTicket> send(List<String> tokens, String title, String body, Map<String, Object> data);

    /**
     * Os recibos (segunda etapa da Expo) dos tickets {@code ticketIds}, por id (recibos-da-expo). Quem
     * nao tem recibo para ler (no-op) devolve vazio. Nunca lanca.
     */
    default Map<String, PushReceipt> fetchReceipts(List<String> ticketIds) {
        return Map.of();
    }
}
