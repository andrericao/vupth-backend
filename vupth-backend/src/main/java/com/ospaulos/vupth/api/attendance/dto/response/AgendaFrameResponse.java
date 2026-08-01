package com.ospaulos.vupth.api.attendance.dto.response;

import java.time.OffsetDateTime;
import java.time.LocalTime;
import java.util.UUID;

/**
 * "Quadro" da agenda de UM profissional (visao de agenda / fatia 2) — a moldura que o Front-Client
 * usa para desenhar a barra de encaixe (particao de disco) e o Front-Partner para a aba Agenda:
 *
 * <ul>
 *   <li>{@code projectedQueueEnd} — fim projetado da fila VIVA agora (piso do agendavel de HOJE:
 *       nao se agenda dentro da fila que ja roda);</li>
 *   <li>{@code openingTime}/{@code closingTime} — janela de expediente da loja (limites da faixa
 *       agendavel por dia).</li>
 * </ul>
 *
 * <p>Os "blocos ocupados" da barra sao as proprias intencoes ({@link IntentionResponse}) que casam
 * pelo {@code professionalId}. No {@code professional/me} vem 1 frame; no {@code client/me} vem um
 * por profissional distinto entre os agendamentos do cliente.</p>
 */
public record AgendaFrameResponse(
        UUID professionalId,
        UUID establishmentId,
        OffsetDateTime projectedQueueEnd,
        LocalTime openingTime,
        LocalTime closingTime
) {
}
