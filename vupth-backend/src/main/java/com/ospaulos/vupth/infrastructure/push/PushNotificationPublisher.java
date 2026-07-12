package com.ospaulos.vupth.infrastructure.push;

import com.ospaulos.vupth.domain.notification.model.DeviceToken;
import com.ospaulos.vupth.domain.notification.repository.DeviceTokenRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Envia uma notificacao push para todos os devices de um usuario (backlog #1).
 *
 * <p>Espelha o padrao do {@code QueueWebSocketPublisher}: resolve os tokens do destino e delega ao
 * {@link PushSender}. Best-effort — sem tokens = no-op silencioso; qualquer falha e logada e nunca
 * quebra a operacao chamadora.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PushNotificationPublisher {

    private final DeviceTokenRepository deviceTokenRepository;
    private final PushSender pushSender;

    /** Notifica todos os devices do usuario. */
    public void notifyUser(UUID userId, String title, String body, Map<String, Object> data) {
        if (userId == null) {
            return;
        }
        try {
            List<String> tokens = deviceTokenRepository.findByUserId(userId).stream()
                    .map(DeviceToken::getToken)
                    .toList();
            if (tokens.isEmpty()) {
                return;
            }
            pushSender.send(tokens, title, body, data);
            log.info("[PUSH] notifyUser user={} title='{}' devices={}", userId, title, tokens.size());
        } catch (Exception e) {
            log.warn("[PUSH] falha ao notificar usuario {}: {}", userId, e.getMessage());
        }
    }
}
