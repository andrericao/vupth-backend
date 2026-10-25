package com.ospaulos.vupth.api.attendance.dto.request;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Manifestacao de intencao de atendimento (backlog #4 / status INTENTION). O cliente registrado
 * pede, pelo app, para ser atendido num horario PRETENDIDO futuro ({@code desiredTime}) — tipicamente
 * com a loja fechada ou o dia cheio. Nao entra na fila viva na hora: nasce como INTENTION (fora da
 * fila) e sera promovido a SCHEDULED perto do horario (fatia 3). O {@code clientId} vem do JWT.
 */
public record CreateIntentionRequest(
        @NotNull UUID professionalId,
        @NotEmpty List<UUID> serviceItemIds,
        // must be ISO-8601 string — enforced by JacksonConfig.StrictOffsetDateTimeDeserializer
        @NotNull OffsetDateTime desiredTime,
        // cupom-ativado-em-configuracoes: o cupom ativado que o cliente escolheu usar neste corte (um so; opcional)
        @com.fasterxml.jackson.annotation.JsonProperty("coupon_campaign_id") @com.fasterxml.jackson.annotation.JsonAlias("couponCampaignId")
        UUID couponCampaignId
) {
    @com.fasterxml.jackson.annotation.JsonCreator
    public CreateIntentionRequest {
    }

    public CreateIntentionRequest(UUID professionalId, List<UUID> serviceItemIds, OffsetDateTime desiredTime) {
        this(professionalId, serviceItemIds, desiredTime, null);
    }
}
