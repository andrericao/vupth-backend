package com.ospaulos.vupth.domain.report.service;

import com.ospaulos.vupth.api.report.dto.response.EstablishmentReportResponse;
import com.ospaulos.vupth.api.report.dto.response.ProfessionalReportResponse;

import java.time.LocalDate;
import java.util.UUID;

public interface ReportService {

    /**
     * Relatorio agregado do establishment (#4 + #5). Apenas o owner.
     *
     * @param userId          usuario autenticado (deve ser owner do establishment)
     * @param establishmentId establishment alvo
     * @param dateFrom        inicio do periodo (inclusivo); null = desde sempre
     * @param dateTo          fim do periodo (inclusivo no dia); null = ate sempre
     */
    EstablishmentReportResponse getEstablishmentReport(
            UUID userId, UUID establishmentId, LocalDate dateFrom, LocalDate dateTo);

    /**
     * Relatorio agregado do proprio profissional autenticado.
     *
     * @param userId   profissional autenticado
     * @param dateFrom inicio do periodo (inclusivo); null = desde sempre
     * @param dateTo   fim do periodo (inclusivo no dia); null = ate sempre
     */
    ProfessionalReportResponse getProfessionalReport(
            UUID userId, LocalDate dateFrom, LocalDate dateTo);
}
