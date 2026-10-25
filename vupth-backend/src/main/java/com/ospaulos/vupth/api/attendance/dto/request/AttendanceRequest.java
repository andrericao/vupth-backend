package com.ospaulos.vupth.api.attendance.dto.request;

import com.ospaulos.vupth.domain.attendance.AttendanceStatus;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record AttendanceRequest(
        UUID professionalId,
        UUID establishmentId,
        UUID clientId,
        List<UUID> serviceItemIds,
        AttendanceStatus status,
        OffsetDateTime scheduledTime, // must be ISO-8601 string — enforced by JacksonConfig.StrictOffsetDateTimeDeserializer
        Integer estimatedMinutes,
        OffsetDateTime local,
        // Item #40 (intencao de fila): horario-alvo opcional. Quando presente, o cliente entra como
        // INTENCAO (cede a vez ate o previsto encostar no alvo). Null = ingresso normal (walk-in).
        OffsetDateTime targetTime,
        // cupom-ativado-em-configuracoes: o cupom ativado que o cliente escolheu usar neste corte (um so; opcional)
        @com.fasterxml.jackson.annotation.JsonProperty("coupon_campaign_id") @com.fasterxml.jackson.annotation.JsonAlias("couponCampaignId") UUID couponCampaignId
) {
    /** Canonico explicito: com os construtores de compatibilidade, e este que o Jackson usa. */
    @com.fasterxml.jackson.annotation.JsonCreator
    public AttendanceRequest {
    }

    /** Construtor de compatibilidade: sem cupom. */
    public AttendanceRequest(
            UUID professionalId,
            UUID establishmentId,
            UUID clientId,
            List<UUID> serviceItemIds,
            AttendanceStatus status,
            OffsetDateTime scheduledTime,
            Integer estimatedMinutes,
            OffsetDateTime local,
            OffsetDateTime targetTime) {
        this(professionalId, establishmentId, clientId, serviceItemIds, status,
                scheduledTime, estimatedMinutes, local, targetTime, null);
    }

    /**
     * Construtor de compatibilidade (ingresso normal / walk-in): sem horario-alvo.
     * Delega ao canonico com {@code targetTime = null}. Jackson continua usando o
     * construtor canonico (9 componentes) para desserializar o JSON.
     */
    public AttendanceRequest(
            UUID professionalId,
            UUID establishmentId,
            UUID clientId,
            List<UUID> serviceItemIds,
            AttendanceStatus status,
            OffsetDateTime scheduledTime,
            Integer estimatedMinutes,
            OffsetDateTime local) {
        this(professionalId, establishmentId, clientId, serviceItemIds, status,
                scheduledTime, estimatedMinutes, local, null, null);
    }
}
