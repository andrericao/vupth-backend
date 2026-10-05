package com.ospaulos.vupth.api.attendance.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Barra de encaixe (#3 / Client B2): a agenda PUBLICA de um profissional. {@code frame} traz o piso
 * da fila viva ({@code projectedQueueEnd}) e o horario de hoje; o horario dos outros dias sai de
 * {@code GET /establishment/{id}/hours}. {@code blocks} sao os horarios ocupados, ANONIMOS — so inicio,
 * duracao e o tipo: nenhum id, cliente ou servico. {@code type} (agenda-publica-com-expediente):
 * {@code INTENTION} = ja reservado por um agendamento; {@code BREAK} = refeicao ou pausa do profissional,
 * de hoje ate o horizonte do agendamento. O app pula os dois do mesmo jeito. {@code days}: o horario de
 * cada dia ({@link Day}).
 */
public record PublicAgendaResponse(AgendaFrameResponse frame, List<Block> blocks, List<Day> days) {

    public static final String INTENTION = "INTENTION";
    public static final String BREAK = "BREAK";

    public record Block(OffsetDateTime start, int durationMinutes, String type) {}

    /**
     * O horario que vale de fato no dia (agenda-do-profissional): a loja ∩ a agenda do profissional, de
     * hoje ate o horizonte do agendamento. Sem horario nenhum configurado: {@code closed=false} sem
     * {@code opens}/{@code closes} (qualquer hora).
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Day(LocalDate date, boolean closed, LocalTime opens, LocalTime closes) {}
}
