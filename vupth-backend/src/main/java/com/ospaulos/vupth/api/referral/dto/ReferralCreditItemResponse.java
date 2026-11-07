package com.ospaulos.vupth.api.referral.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Um premio de indicacao de quem o recebe (premio-em-pendentes, Partner: Seu dia -> Pendentes), com o atendimento que o gerou.
 * {@code status}: {@code LOCKED} (ganho, espera o Andre liberar), {@code TO_PAY} (liberado, a pagar) ou {@code PAID}. Do cliente vai
 * so o primeiro nome. {@code test} (premio-de-teste-fora-do-a-pagar): veio de um corte de teste; fica LOCKED, nao soma nem se paga.
 */
public record ReferralCreditItemResponse(
        @JsonProperty("credit_id") UUID creditId,
        @JsonProperty("campaign_id") UUID campaignId,
        @JsonProperty("campaign_name") String campaignName,
        @JsonProperty("amount") BigDecimal amount,
        @JsonProperty("share") String share,
        @JsonProperty("status") String status,
        @JsonProperty("attendance_id") UUID attendanceId,
        @JsonProperty("client_name") String clientName,
        @JsonProperty("finished_at") OffsetDateTime finishedAt,
        @JsonProperty("created_at") OffsetDateTime createdAt,
        @JsonProperty("released_at") OffsetDateTime releasedAt,
        @JsonProperty("paid_at") OffsetDateTime paidAt,
        @JsonProperty("test") boolean test
) {
    public static String statusOf(OffsetDateTime releasedAt, OffsetDateTime paidAt) {
        if (paidAt != null) {
            return "PAID";
        }
        return releasedAt == null ? "LOCKED" : "TO_PAY";
    }
}
