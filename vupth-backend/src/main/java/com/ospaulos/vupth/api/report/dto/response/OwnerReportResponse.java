package com.ospaulos.vupth.api.report.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * GET /owner/me/reports (multi-loja-do-dono): as lojas do dono juntas ({@code total}), cada uma ({@code stores})
 * e o tempo ({@code series}, por dia, semana ou mes da loja). {@code not_realized} = quantos atendimentos nao aconteceram, pelos tres motivos, e {@code finished_late} = os concluidos
 * em que o cliente chegou atrasado; sem valor de multa.
 */
public record OwnerReportResponse(
        @JsonProperty("total") Summary total,
        @JsonProperty("stores") List<Store> stores,
        @JsonProperty("series") List<Period> series
) {

    /** Os numeros de um conjunto de atendimentos. {@code grossRevenue} = o que o cliente pagou. */
    public record Summary(
            @JsonProperty("finished") long finished,
            @JsonProperty("cancelled") long cancelled,
            @JsonProperty("gross_revenue") BigDecimal grossRevenue,
            @JsonProperty("professionals_net") BigDecimal professionalsNet,
            @JsonProperty("gateway_fee") BigDecimal gatewayFee,
            @JsonProperty("app_fee") BigDecimal appFee,
            @JsonProperty("not_realized") NotRealized notRealized,
            @JsonProperty("finished_late") long finishedLate
    ) {}

    /** Os tres motivos de um atendimento nao realizado (DEC-83): o cliente cancelou, o profissional removeu o cliente, o cliente foi
     * removido por atraso. So a contagem: a multa e do app. */
    public record NotRealized(
            @JsonProperty("client_cancelled") long clientCancelled,
            @JsonProperty("professional_removed") long professionalRemoved,
            @JsonProperty("removed_for_lateness") long removedForLateness
    ) {}

    public record Store(
            @JsonProperty("id") UUID id,
            @JsonProperty("name") String name,
            @JsonProperty("summary") Summary summary
    ) {}

    /** Um periodo com atendimento; {@code from} e o primeiro dia (a segunda, na semana; o dia 1, no mes). */
    public record Period(
            @JsonProperty("from") LocalDate from,
            @JsonProperty("summary") Summary summary
    ) {}
}
