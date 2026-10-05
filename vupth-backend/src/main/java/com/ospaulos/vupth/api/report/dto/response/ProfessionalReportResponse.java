package com.ospaulos.vupth.api.report.dto.response;

import java.math.BigDecimal;
import java.util.List;

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
        String topService,
        // Receita LIQUIDA: soma do que o profissional recebe nos atendimentos com divisao congelada.
        BigDecimal netRevenue,
        // Atendimentos SEM divisao (servico legado / anteriores a 16/09): se > 0, o liquido esta
        // incompleto e o app deve dizer isso em vez de mostrar o numero como o todo.
        long attendancesWithoutSplit,
        // Expediente real do profissional por dia (Partner S4).
        List<WorkdayResponse> workdays
) {}
