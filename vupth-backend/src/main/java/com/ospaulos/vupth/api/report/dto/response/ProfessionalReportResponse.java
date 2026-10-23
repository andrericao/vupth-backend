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
        List<WorkdayResponse> workdays,
        // pagamento-pix (10/10): dos finalizados do periodo, quantos ainda nao foram pagos (PENDING ou
        // AWAITING_CONFIRMATION) e o valor dos servicos deles. Pago, sai daqui.
        long paymentPendingCount,
        BigDecimal paymentPendingValue,
        // relatorio-sem-multa (DEC-83): os nao realizados pelos tres motivos e os concluidos com atraso do cliente.
        // So a contagem: a multa e do app.
        OwnerReportResponse.NotRealized notRealized,
        long finishedLate,
        // relatorio-do-profissional-recebimento (PAR-3): o que o profissional recebe e as taxas (as do relatorio do dono) por corte e
        // no periodo; a divida do pagamento por fora (DEC-109) so a REAL aberta entra em pending_debt (a simulada, de teste, vai a parte).
        @com.fasterxml.jackson.annotation.JsonProperty("receives_total") BigDecimal receivesTotal,
        @com.fasterxml.jackson.annotation.JsonProperty("fees_total") BigDecimal feesTotal,
        @com.fasterxml.jackson.annotation.JsonProperty("pending_debt_total") BigDecimal pendingDebtTotal,
        @com.fasterxml.jackson.annotation.JsonProperty("pending_debt_simulated_total") BigDecimal pendingDebtSimulatedTotal,
        List<Cut> cuts
) {

    /** Um corte finalizado: {@code receives} e {@code fees} nulos quando o servico e legado (sem divisao congelada). */
    public record Cut(
            @com.fasterxml.jackson.annotation.JsonProperty("attendance_id") java.util.UUID attendanceId,
            @com.fasterxml.jackson.annotation.JsonProperty("finished_at") java.time.OffsetDateTime finishedAt,
            @com.fasterxml.jackson.annotation.JsonProperty("total_value") BigDecimal totalValue,
            BigDecimal receives,
            BigDecimal fees,
            @com.fasterxml.jackson.annotation.JsonProperty("pending_debt") BigDecimal pendingDebt,
            @com.fasterxml.jackson.annotation.JsonProperty("pending_debt_simulated") BigDecimal pendingDebtSimulated) {
    }
}
