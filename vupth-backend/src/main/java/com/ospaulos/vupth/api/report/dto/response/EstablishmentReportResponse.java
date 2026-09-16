package com.ospaulos.vupth.api.report.dto.response;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Relatorio agregado do establishment para o owner (#4 + #5).
 *
 * Totais do periodo (atendimentos FINALIZADOS) + breakdown de receita por
 * profissional (para calculo de comissoes). Nenhuma lista de atendimentos volta:
 * o backend ja entrega os numeros agregados, evitando agregacao client-side.
 *
 * @param totalAttendances   quantidade de atendimentos FINISHED no periodo
 * @param totalRevenue       soma de total_value dos FINISHED
 * @param avgDurationMinutes media de duration_minutes (0 se nao houver dados)
 * @param topService         ServiceItemType mais frequente (nome do enum; null se vazio)
 * @param topProfessional    nome do profissional com mais atendimentos (null se vazio)
 * @param professionals      breakdown por profissional, ordenado por receita desc
 */
public record EstablishmentReportResponse(
        long totalAttendances,
        BigDecimal totalRevenue,
        double avgDurationMinutes,
        String topService,
        String topProfessional,
        List<ProfessionalRevenue> professionals,
        // Receita LIQUIDA (ver ProfessionalReportResponse): total da loja e contagem dos sem divisao.
        BigDecimal netRevenue,
        long attendancesWithoutSplit
) {

    public record ProfessionalRevenue(
            UUID id,
            String name,
            BigDecimal revenue,
            long attendances,
            BigDecimal netRevenue
    ) {}
}
