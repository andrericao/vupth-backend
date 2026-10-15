package com.ospaulos.vupth.api.campaign.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.ospaulos.vupth.domain.campaign.model.Campaign;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/** Uma campanha como o app mostra: o que e, quanto vale e ate quando. O cupom so sai para quem ja o tem. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CampaignResponse(
        @JsonProperty("id") UUID id,
        @JsonProperty("name") String name,
        @JsonProperty("description") String description,
        @JsonProperty("type") String type,
        @JsonProperty("audience") String audience,
        @JsonProperty("value_amount") BigDecimal valueAmount,
        @JsonProperty("value_percent") BigDecimal valuePercent,
        @JsonProperty("starts_at") OffsetDateTime startsAt,
        @JsonProperty("ends_at") OffsetDateTime endsAt,
        @JsonProperty("weekdays") String weekdays,
        @JsonProperty("code") String code,
        @JsonProperty("max_redemptions") Integer maxRedemptions,
        @JsonProperty("remaining_slots") Integer remainingSlots,
        @JsonProperty("new_clients_only") boolean newClientsOnly,
        @JsonProperty("redeemed_at") OffsetDateTime redeemedAt
) {
    /** {@code used} = quantos ja aceitaram (para as vagas restantes); nulo quando nao interessa. */
    public static CampaignResponse of(Campaign c, boolean withCode, Long used, OffsetDateTime redeemedAt) {
        Integer remaining = c.getMaxRedemptions() == null || used == null
                ? null : (int) Math.max(0, c.getMaxRedemptions() - used);
        return new CampaignResponse(c.getId(), c.getName(), c.getDescription(), c.getType().name(),
                c.getAudience() == null ? null : c.getAudience().name(), c.getValueAmount(), c.getValuePercent(),
                c.getStartsAt(), c.getEndsAt(), c.getWeekdays(), withCode ? c.getCode() : null,
                c.getMaxRedemptions(), remaining, c.isNewClientsOnly(), redeemedAt);
    }
}
