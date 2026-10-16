package com.ospaulos.vupth.api.campaign.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.ospaulos.vupth.domain.campaign.model.Campaign;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A campanha como o painel mostra: tudo o que o app ve, mais a visibilidade, quantos aceitaram e o estado
 * ({@code SCHEDULED} antes do inicio, {@code RUNNING} no ar, {@code PAUSED} desligada dentro da janela,
 * {@code ENDED} depois do fim).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AdminCampaignResponse(
        @JsonProperty("id") UUID id,
        @JsonProperty("name") String name,
        @JsonProperty("description") String description,
        @JsonProperty("code") String code,
        @JsonProperty("type") String type,
        @JsonProperty("visibility") String visibility,
        @JsonProperty("audience") String audience,
        @JsonProperty("value_amount") BigDecimal valueAmount,
        @JsonProperty("value_percent") BigDecimal valuePercent,
        @JsonProperty("starts_at") OffsetDateTime startsAt,
        @JsonProperty("ends_at") OffsetDateTime endsAt,
        @JsonProperty("weekdays") String weekdays,
        @JsonProperty("max_redemptions") Integer maxRedemptions,
        @JsonProperty("redemptions") long redemptions,
        @JsonProperty("new_clients_only") boolean newClientsOnly,
        @JsonProperty("active") boolean active,
        @JsonProperty("status") String status
) {
    public static AdminCampaignResponse of(Campaign c, long redemptions, OffsetDateTime now) {
        return new AdminCampaignResponse(c.getId(), c.getName(), c.getDescription(), c.getCode(), c.getType().name(),
                c.getVisibility().name(), c.getAudience() == null ? null : c.getAudience().name(), c.getValueAmount(),
                c.getValuePercent(), c.getStartsAt(), c.getEndsAt(), c.getWeekdays(), c.getMaxRedemptions(),
                redemptions, c.isNewClientsOnly(), c.isActive(), status(c, now));
    }

    private static String status(Campaign c, OffsetDateTime now) {
        if (!now.isBefore(c.getEndsAt())) return "ENDED";
        if (!c.isActive()) return "PAUSED";
        return now.isBefore(c.getStartsAt()) ? "SCHEDULED" : "RUNNING";
    }
}
