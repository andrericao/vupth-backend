package com.ospaulos.vupth.api.report.dto.response;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * Expediente real de um dia (Partner S4) — o "Expediente: HH:MM–HH:MM" dos relatorios.
 *
 * @param date          dia da loja (fuso de negocio) em que o expediente comecou
 * @param start         primeira entrada em expediente do dia
 * @param end           ultima saida do dia; {@code null} enquanto alguem segue em expediente
 * @param workedMinutes minutos em expediente no dia, sem contar duas vezes o tempo em que mais de um
 *                      profissional estava presente; o turno em andamento conta ate agora
 */
public record WorkdayResponse(
        LocalDate date,
        OffsetDateTime start,
        OffsetDateTime end,
        long workedMinutes
) {}
