package com.ospaulos.vupth.api.referral.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * lista-de-premios-no-painel: um premio de indicacao. {@code professional}: quem cortou; {@code beneficiary}: quem recebe; {@code
 * client_name}: o indicado, so o primeiro nome. {@code status}: {@code LOCKED}, {@code TO_PAY} ou {@code PAID}.
 */
public record AdminReferralCreditRow(
        @JsonProperty("credit_id") UUID creditId,
        @JsonProperty("campaign_id") UUID campaignId,
        @JsonProperty("campaign_name") String campaignName,
        @JsonProperty("professional_id") UUID professionalId,
        @JsonProperty("professional_name") String professionalName,
        @JsonProperty("beneficiary_id") UUID beneficiaryId,
        @JsonProperty("beneficiary_name") String beneficiaryName,
        @JsonProperty("client_name") String clientName,
        @JsonProperty("attendance_id") UUID attendanceId,
        @JsonProperty("share") String share,
        @JsonProperty("amount") BigDecimal amount,
        @JsonProperty("status") String status,
        @JsonProperty("created_at") OffsetDateTime createdAt,
        @JsonProperty("released_at") OffsetDateTime releasedAt,
        @JsonProperty("paid_at") OffsetDateTime paidAt,
        @JsonProperty("test") boolean test) {
}
