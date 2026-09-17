package com.ospaulos.vupth.api.attendance.dto.response;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Barra de encaixe (#3 / Client B2): a agenda PUBLICA de um profissional. {@code frame} traz o piso
 * da fila viva ({@code projectedQueueEnd}) e o horario de hoje; o horario dos outros dias sai de
 * {@code GET /establishment/{id}/hours}. {@code blocks} sao os horarios ja reservados por intencoes,
 * ANONIMOS — so inicio e duracao: nenhum id, cliente ou servico.
 */
public record PublicAgendaResponse(AgendaFrameResponse frame, List<Block> blocks) {

    public record Block(OffsetDateTime start, int durationMinutes) {}
}
