package com.ospaulos.vupth.api.attendance.dto.response;

import java.util.List;

/**
 * Visao de agenda (backlog #4, fatia 2) — os atendimentos FUTUROS que ainda nao estao na fila viva
 * (status {@code INTENTION}), congelados no estimado da criacao. Mesma forma para as duas telas:
 *
 * <ul>
 *   <li>{@code GET /attendances/intentions/professional/me} (profissional) — a agenda dele; 1 frame.</li>
 *   <li>{@code GET /attendances/intentions/client/me} (cliente) — os proprios agendamentos em qualquer
 *       loja; um frame por profissional distinto.</li>
 * </ul>
 *
 * <p>{@code frames} = a moldura da barra de encaixe por profissional; {@code intentions} = os blocos
 * congelados (cada um aponta seu {@code professionalId} para casar com um frame). O front agrupa as
 * intencoes por dia (pelo {@code desiredTime}).</p>
 */
public record IntentionAgendaResponse(
        List<AgendaFrameResponse> frames,
        List<IntentionResponse> intentions
) {
}
