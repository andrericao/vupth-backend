package com.ospaulos.vupth.api.attendance.dto.response;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Barra de encaixe (#3 / Client B2): a agenda PUBLICA de um profissional. {@code frame} traz o piso
 * da fila viva ({@code projectedQueueEnd}) e o horario de hoje; o horario dos outros dias sai de
 * {@code GET /establishment/{id}/hours}. {@code blocks} sao os horarios ocupados, ANONIMOS — so inicio,
 * duracao e o tipo: nenhum id, cliente ou servico. {@code type} (agenda-publica-com-expediente):
 * {@code INTENTION} = ja reservado por um agendamento; {@code BREAK} = refeicao ou pausa do profissional,
 * de hoje ate o horizonte do agendamento. O app pula os dois do mesmo jeito.
 */
public record PublicAgendaResponse(AgendaFrameResponse frame, List<Block> blocks) {

    public static final String INTENTION = "INTENTION";
    public static final String BREAK = "BREAK";

    public record Block(OffsetDateTime start, int durationMinutes, String type) {}
}
