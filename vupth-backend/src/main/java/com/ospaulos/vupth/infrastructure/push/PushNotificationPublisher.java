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
 *
 * <p><b>Backlog #28 — a limpeza de token morto mora aqui.</b> Este e o unico ponto que ja conhece a
 * base de devices, entao a politica de ciclo de vida do token fica com ele e o {@link PushSender}
 * segue sendo transporte puro. Ticket com {@code DeviceNotRegistered} = a Expo afirma que aquele
 * aparelho nao recebe mais push (app desinstalado ou token rotacionado): o registro sai da base.
 * Antes disso, o unico caminho de saida era o logout explicito ({@code DELETE /devices}) — quem
 * desinstalasse o app deixaria o token para tras <b>para sempre</b>, e todo push seguinte seria
 * gasto no vazio.</p>
 *
 * <p>Apagar e seguro porque o registro se refaz sozinho: {@code POST /devices} e um upsert por
 * token, entao reinstalar o app recria a linha no proximo login.</p>
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
            List<PushTicket> tickets = pushSender.send(tokens, title, body, data);
            log.info("[PUSH] notifyUser user={} title='{}' devices={}", userId, title, tokens.size());
            purgeDeadTokens(userId, tickets);
        } catch (Exception e) {
            log.warn("[PUSH] falha ao notificar usuario {}: {}", userId, e.getMessage());
        }
    }

    /**
     * Remove da base os tokens que a Expo declarou mortos. Nao lanca: a limpeza e manutencao, e
     * falhar nela nao pode transformar um push entregue em erro para o chamador.
     */
    private void purgeDeadTokens(UUID userId, List<PushTicket> tickets) {
        if (tickets == null || tickets.isEmpty()) {
            return;
        }
        for (PushTicket ticket : tickets) {
            if (!ticket.isDeviceGone()) {
                continue;
            }
            try {
                deviceTokenRepository.deleteByToken(ticket.token());
                log.info("[PUSH] device token removido (DeviceNotRegistered) — user={} token={}",
                        userId, PushTokenMask.mask(ticket.token()));
            } catch (Exception e) {
                log.warn("[PUSH] falha ao remover device token morto {} do usuario {}: {}",
                        PushTokenMask.mask(ticket.token()), userId, e.getMessage());
            }
        }
    }
}
