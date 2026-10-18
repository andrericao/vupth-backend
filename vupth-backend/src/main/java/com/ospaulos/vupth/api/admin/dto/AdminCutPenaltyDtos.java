package com.ospaulos.vupth.api.admin.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * O relatorio do ADMIN do que o app ganha no corte e na multa, separados (relatorio-corte-e-multa-por-atendimento,
 * DEC-93). So o painel (permissao FINANCE): traz a taxa do Asaas e o lucro do app, que nenhum outro perfil ve (DEC-94).
 */
public final class AdminCutPenaltyDtos {

    private AdminCutPenaltyDtos() {}

    /** Uma parte do dinheiro: o que o cliente pagou por ela, a taxa do Asaas atribuida e o que sobra ao app. */
    public record Part(
            @JsonProperty("amount") BigDecimal amount,
            @JsonProperty("asaas_fee") BigDecimal asaasFee,
            @JsonProperty("app_profit") BigDecimal appProfit) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Row(
            @JsonProperty("key") String key,
            @JsonProperty("attendance_id") UUID attendanceId,
            @JsonProperty("paid_at") OffsetDateTime paidAt,
            @JsonProperty("attendances") long attendances,
            @JsonProperty("service") Part service,
            @JsonProperty("penalty") Part penalty,
            @JsonProperty("total") Part total) {}

    public record Report(
            @JsonProperty("period") AdminMetricsDtos.Window period,
            @JsonProperty("group_by") String groupBy,
            @JsonProperty("items") List<Row> items,
            @JsonProperty("totals") Row totals,
            @JsonProperty("services_without_split") long servicesWithoutSplit) {}
}
