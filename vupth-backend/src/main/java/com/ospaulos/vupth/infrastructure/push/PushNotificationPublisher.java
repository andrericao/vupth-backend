package com.ospaulos.vupth.infrastructure.push;

import com.ospaulos.vupth.domain.notification.model.DeviceToken;
import com.ospaulos.vupth.domain.notification.model.PendingPushReceipt;
import com.ospaulos.vupth.domain.notification.repository.DeviceTokenRepository;
import com.ospaulos.vupth.domain.notification.repository.PendingPushReceiptRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
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
 *
 * <p><b>recibos-da-expo:</b> cada ticket aceito com id vira uma linha de {@link PendingPushReceipt};
 * o {@link PushReceiptChecker} le o recibo depois.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PushNotificationPublisher {

    private final DeviceTokenRepository deviceTokenRepository;
    private final PushSender pushSender;
    private final PendingPushReceiptRepository pendingReceiptRepository;

    /**
     * Notifica todos os devices do usuario. Devolve se o envio saiu: {@code false} sem usuario, sem aparelho registrado ou se o envio
     * falhou. Quem marca "ja avisado" (ex.: o "e quase a sua vez") so deve marcar com {@code true}; senao o aviso se perde para sempre
     * quando o token do aparelho chega depois da vez. Quem ignora o retorno segue como antes.
     */
    public boolean notifyUser(UUID userId, String title, String body, Map<String, Object> data) {
        if (userId == null) {
            return false;
        }
        try {
            List<String> tokens = deviceTokenRepository.findByUserId(userId).stream()
                    .map(DeviceToken::getToken)
                    .toList();
            if (tokens.isEmpty()) {
                // S43 (push-conta-errada): sem esta linha, "nao chegou" e "nao havia aparelho" ficavam
                // iguais no log — o e-mail saia e o push sumia em silencio.
                log.info("[PUSH] notifyUser user={} title='{}' devices=0 — nenhum aparelho registrado", userId, title);
                return false;
            }
            List<PushTicket> tickets = pushSender.send(tokens, title, body, data);
            log.info("[PUSH] notifyUser user={} title='{}' devices={}", userId, title, tokens.size());
            logTickets(userId, data, tickets);
            purgeDeadTokens(userId, tickets);
            awaitReceipts(userId, title, tickets);
            return true;
        } catch (Exception e) {
            log.warn("[PUSH] falha ao notificar usuario {}: {}", userId, e.getMessage());
            return false;
        }
    }

    /**
     * push-quase-a-vez-nao-chega (31/10): uma linha por ticket da Expo, com o tipo do push, o fim do token, se a Expo aceitou, o id
     * (o mesmo que o recibo cita depois) e o erro. Com isso o Render responde "saiu e a Expo aceitou?" na hora, sem esperar o recibo
     * (~15 min). Nada de token inteiro nem de texto do push.
     */
    private void logTickets(UUID userId, Map<String, Object> data, List<PushTicket> tickets) {
        if (tickets == null) {
            return;
        }
        Object type = data == null ? null : data.get("type");
        for (PushTicket t : tickets) {
            String token = t.token() == null ? "?" : "***" + t.token().substring(Math.max(0, t.token().length() - 5));
            if (t.ok()) {
                log.info("[PUSH] ticket user={} type={} token={} ok id={}", userId, type, token, t.id());
            } else {
                log.warn("[PUSH] ticket user={} type={} token={} RECUSADO error={} msg={}", userId, type, token, t.error(),
                        t.message() == null ? null : (t.message().length() > 120 ? t.message().substring(0, 120) : t.message()));
            }
        }
    }

    /** Guarda os tickets aceitos para ler o recibo depois. Nao lanca: falhar aqui nao desfaz o envio. */
    private void awaitReceipts(UUID userId, String title, List<PushTicket> tickets) {
        if (tickets == null || tickets.isEmpty()) {
            return;
        }
        OffsetDateTime now = OffsetDateTime.now();
        String shortTitle = title == null || title.length() <= 200 ? title : title.substring(0, 200);
        List<PendingPushReceipt> pending = tickets.stream()
                .filter(t -> t.ok() && t.id() != null && !t.id().isBlank())
                .map(t -> new PendingPushReceipt(t.id(), t.token(), userId, shortTitle, now))
                .toList();
        if (pending.isEmpty()) {
            return;
        }
        try {
            pendingReceiptRepository.saveAll(pending);
        } catch (Exception e) {
            log.warn("[PUSH] falha ao guardar {} ticket(s) para o recibo do usuario {}: {}",
                    pending.size(), userId, e.getMessage());
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
