package com.ospaulos.vupth.infrastructure.websocket;

import com.ospaulos.vupth.api.establishment.usecase.GetEstablishmentWithQueueUseCase;
import com.ospaulos.vupth.api.websocket.dto.QueueNotification;
import com.ospaulos.vupth.domain.notification.service.QueuePushService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Publica eventos da fila via WebSocket.
 *
 * Chamado pela camada web apos a mutacao do atendimento (transacao ja commitada),
 * entao o snapshot rebroadcastado reflete o estado persistido.
 *
 * Falhas de envio sao logadas e engolidas — o WebSocket e um canal best-effort
 * e nunca deve quebrar a resposta HTTP da operacao principal.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QueueWebSocketPublisher {

    private final SimpMessagingTemplate messagingTemplate;
    private final GetEstablishmentWithQueueUseCase getWithQueueUseCase;
    private final QueuePushService queuePushService;

    /**
     * Broadcast do snapshot atualizado da fila para todos que observam a loja, e — como a fila mudou —
     * dispara o push "voce e o proximo" para quem passou a ser a frente da fila (item de notificacoes,
     * deduplicado pela flag {@code next_notified}). Ambos best-effort: falha nunca quebra a resposta HTTP.
     */
    public void broadcastQueue(UUID establishmentId) {
        if (establishmentId == null) {
            return;
        }
        try {
            var snapshot = getWithQueueUseCase.execute(establishmentId);
            messagingTemplate.convertAndSend(
                    "/topic/establishment/" + establishmentId + "/queue", snapshot);
            log.info("[WS] broadcast fila — establishment={}", establishmentId);
        } catch (Exception e) {
            log.warn("[WS] falha ao broadcastar fila do establishment {}: {}",
                    establishmentId, e.getMessage());
        }
        try {
            queuePushService.notifyNextInLine(establishmentId);
        } catch (Exception e) {
            log.warn("[PUSH] falha ao avaliar next-in-line do establishment {}: {}",
                    establishmentId, e.getMessage());
        }
    }

    /** Notificacao pessoal direcionada a um cliente especifico (por userId). */
    public void notifyClient(UUID clientId, QueueNotification notification) {
        if (clientId == null || notification == null) {
            return;
        }
        try {
            messagingTemplate.convertAndSendToUser(
                    clientId.toString(), "/queue/notifications", notification);
            log.info("[WS] notificacao pessoal — client={} type={}", clientId, notification.type());
        } catch (Exception e) {
            log.warn("[WS] falha ao notificar cliente {}: {}", clientId, e.getMessage());
        }
    }
}
