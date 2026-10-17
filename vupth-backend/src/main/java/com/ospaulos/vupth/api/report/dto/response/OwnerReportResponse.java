package com.ospaulos.vupth.api.report.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * GET /owner/me/reports (multi-loja-do-dono): as lojas do dono juntas ({@code total}), cada uma ({@code stores})
 * e o tempo ({@code series}, por dia, semana ou mes da loja). {@code penalties} = as multas geradas por atendimentos da loja (o dinheiro delas e do app).
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
            @JsonProperty("penalties") BigDecimal penalties
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
