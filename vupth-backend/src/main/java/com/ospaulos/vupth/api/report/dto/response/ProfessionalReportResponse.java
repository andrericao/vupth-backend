package com.ospaulos.vupth.api.report.dto.response;

import java.math.BigDecimal;

/**
 * Relatorio agregado do proprio profissional (GET /professional/me/reports).
 *
 * Totais do periodo (atendimentos FINALIZADOS do profissional autenticado),
 * para as telas de relatorio dia/semana/mes do app parceiro.
 *
 * @param totalAttendances   quantidade de atendimentos FINISHED no periodo
 * @param totalRevenue       soma de total_value dos FINISHED
 * @param avgDurationMinutes media de duration_minutes (0 se nao houver dados)
 * @param topService         ServiceItemType mais frequente (nome do enum; null se vazio)
 */
public record ProfessionalReportResponse(
        long totalAttendances,
        BigDecimal totalRevenue,
        double avgDurationMinutes,
        String topService
) {}
