package com.ospaulos.vupth.api.campaign.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.ospaulos.vupth.domain.campaign.model.CampaignAudience;
import com.ospaulos.vupth.domain.campaign.model.CampaignType;
import com.ospaulos.vupth.domain.campaign.model.CampaignVisibility;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * O que o Andre preenche no painel para criar ou editar uma campanha (campanha-pelo-painel, DEC-69): nome,
 * valores, quantidade de pessoas e o perfil de quem pode aceitar. As regras entre campos (janela, cupom da
 * privada, um valor so) ficam no {@code AdminCampaignService}, com 422 {@code CAMPAIGN_INVALID}.
 */
public record AdminCampaignRequest(
        @JsonProperty("name") @NotBlank @Size(max = 80) String name,
        @JsonProperty("description") @Size(max = 300) String description,
        @JsonProperty("code") @Size(max = 40) @Pattern(regexp = "[A-Za-z0-9_-]+", message = "code: so letras, numeros, - e _")
        String code,
        @JsonProperty("type") @NotNull CampaignType type,
        @JsonProperty("visibility") @NotNull CampaignVisibility visibility,
        @JsonProperty("audience") CampaignAudience audience,
        @JsonProperty("value_amount") @DecimalMin("0.01") @Digits(integer = 8, fraction = 2) BigDecimal valueAmount,
        @JsonProperty("value_percent") @DecimalMin("0.01") @DecimalMax("100") @Digits(integer = 3, fraction = 2)
        BigDecimal valuePercent,
        @JsonProperty("starts_at") @NotNull OffsetDateTime startsAt,
        @JsonProperty("ends_at") @NotNull OffsetDateTime endsAt,
        @JsonProperty("weekdays") @Size(max = 80)
        @Pattern(regexp = "((MONDAY|TUESDAY|WEDNESDAY|THURSDAY|FRIDAY|SATURDAY|SUNDAY),)*(MONDAY|TUESDAY|WEDNESDAY|THURSDAY|FRIDAY|SATURDAY|SUNDAY)",
                message = "weekdays: dias em ingles, separados por virgula (ex.: TUESDAY,WEDNESDAY)")
        String weekdays,
        @JsonProperty("max_redemptions") @Min(1) Integer maxRedemptions,
        @JsonProperty("new_clients_only") boolean newClientsOnly
) {
}
