package com.ospaulos.vupth.infrastructure.websocket;

import com.ospaulos.vupth.api.establishment.usecase.GetEstablishmentWithQueueUseCase;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.MimeTypeUtils;
import org.springframework.web.socket.messaging.SessionSubscribeEvent;

import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * snapshot-ao-assinar (05/10): quem assina {@code /topic/establishment/{id}/queue} recebe na hora o estado
 * atual da fila — o mesmo snapshot do {@link QueueWebSocketPublisher#broadcastQueue}.
 *
 * <p>Todo deploy ou restart no Railway derruba os sockets; os apps reconectam em ~5 s e assinam de novo,
 * mas o broker e em memoria e o que foi publicado durante a queda se perdeu. Sem isto a tela da fila
 * ficava velha ate a proxima mudanca naquela loja. Vale tambem para a primeira assinatura.</p>
 *
 * <p>Vai direto para a sessao que assinou (canal de saida, com o id da assinatura), e nao para o topico:
 * o evento sai antes de o broker registrar a assinatura, e um broadcast poderia nao chegar justo a ela;
 * os outros assinantes ja estao em dia. Nao reavalia o push de next-in-line (a fila nao mudou).
 * Best-effort, como o resto do WebSocket: falha so loga.</p>
 */
@Slf4j
@Component
public class QueueSnapshotOnSubscribeListener {

    private static final Pattern QUEUE_TOPIC =
            Pattern.compile("^/topic/establishment/([0-9a-fA-F-]{36})/queue$");

    private final GetEstablishmentWithQueueUseCase getWithQueueUseCase;
    private final SimpMessagingTemplate messagingTemplate;
    private final MessageChannel clientOutboundChannel;

    public QueueSnapshotOnSubscribeListener(
            GetEstablishmentWithQueueUseCase getWithQueueUseCase,
            SimpMessagingTemplate messagingTemplate,
            @Qualifier("clientOutboundChannel") MessageChannel clientOutboundChannel) {
        this.getWithQueueUseCase = getWithQueueUseCase;
        this.messagingTemplate = messagingTemplate;
        this.clientOutboundChannel = clientOutboundChannel;
    }

    @EventListener
    public void onSubscribe(SessionSubscribeEvent event) {
        SimpMessageHeaderAccessor subscribe = SimpMessageHeaderAccessor.wrap(event.getMessage());
        String destination = subscribe.getDestination();
        Matcher matcher = destination == null ? null : QUEUE_TOPIC.matcher(destination);
        if (matcher == null || !matcher.matches()) {
            return;
        }
        try {
            UUID establishmentId = UUID.fromString(matcher.group(1));
            var snapshot = getWithQueueUseCase.execute(establishmentId);

            SimpMessageHeaderAccessor headers = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
            headers.setSessionId(subscribe.getSessionId());
            headers.setSubscriptionId(subscribe.getSubscriptionId());
            headers.setDestination(destination);
            headers.setContentType(MimeTypeUtils.APPLICATION_JSON);
            headers.setLeaveMutable(true);
            Message<?> message = messagingTemplate.getMessageConverter()
                    .toMessage(snapshot, headers.getMessageHeaders());
            if (message == null) {
                log.warn("[WS] snapshot ao assinar sem conversor — establishment={}", establishmentId);
                return;
            }
            clientOutboundChannel.send(message);
            log.info("[WS] snapshot ao assinar — establishment={} session={}", establishmentId,
                    subscribe.getSessionId());
        } catch (Exception e) {
            log.warn("[WS] falha no snapshot ao assinar {}: {}", destination, e.getMessage());
        }
    }
}
