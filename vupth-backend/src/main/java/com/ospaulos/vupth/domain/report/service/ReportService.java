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

    /**
     * Relatorio do dono com todas as lojas dele juntas, cada uma e a serie por periodo (multi-loja-do-dono).
     *
     * @param groupBy          {@code day}, {@code week} (segunda a domingo) ou {@code month}; nulo = {@code day}
     * @param establishmentIds quais lojas; vazio = todas as do dono (id de loja alheia: acesso negado)
     */
    com.ospaulos.vupth.api.report.dto.response.OwnerReportResponse getOwnerReport(
            UUID userId, LocalDate dateFrom, LocalDate dateTo, String groupBy, java.util.Collection<UUID> establishmentIds);
}
