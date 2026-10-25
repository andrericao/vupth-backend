package com.ospaulos.vupth.api.attendance.dto.request;

import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Corpo de {@code POST /attendances/cut-requests} (BUG5 C2). O cliente vem do JWT — nao entra aqui.
 */
public record CutRequestRequest(
        @NotNull UUID professionalId,
        @NotNull UUID establishmentId,
        @NotEmpty List<UUID> serviceItemIds,

        // D6 (Partner S26): dia e horario desejados (camelCase como o resto do corpo e o desiredTime da
        // intencao; a resposta devolve desired_time); opcional — ausente = "assim que abrir".
        // No passado -> 422 VALIDATION_ERROR (a validacao padrao do @Valid).
        @Future(message = "desiredTime must be in the future")
        OffsetDateTime desiredTime,

        // cupom-no-pedido-de-corte (24/10): o cupom ativado que o cliente escolheu usar neste corte (um so; opcional). Vale quando
        // o profissional aceita; o pedido ja recusa (422 COUPON_NOT_ACTIVE) se o cupom nao vale agora.
        @com.fasterxml.jackson.annotation.JsonProperty("coupon_campaign_id")
        @com.fasterxml.jackson.annotation.JsonAlias("couponCampaignId")
        UUID couponCampaignId
) {
    /** Canonico explicito: com os construtores de compatibilidade, e este que o Jackson usa. */
    @com.fasterxml.jackson.annotation.JsonCreator
    public CutRequestRequest {
    }

    public CutRequestRequest(UUID professionalId, UUID establishmentId, List<UUID> serviceItemIds, OffsetDateTime desiredTime) {
        this(professionalId, establishmentId, serviceItemIds, desiredTime, null);
    }

    public CutRequestRequest(UUID professionalId, UUID establishmentId, List<UUID> serviceItemIds) {
        this(professionalId, establishmentId, serviceItemIds, null, null);
    }
}
