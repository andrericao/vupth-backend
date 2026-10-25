package com.ospaulos.vupth.api.attendance.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.ospaulos.vupth.domain.attendance.cut.model.CutRequest;
import com.ospaulos.vupth.domain.serviceItem.model.ServiceItem;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Read-model do pedido de corte (BUG5 C2). {@code resultingAttendanceId} so vem preenchido apos o
 * aceite (fatia 2).
 */
public record CutRequestResponse(
        UUID id,

        @JsonProperty("professional_id")
        UUID professionalId,

        @JsonProperty("establishment_id")
        UUID establishmentId,

        @JsonProperty("client_id")
        UUID clientId,

        /** Nome do cliente que pediu — o Partner monta o card sem um GET a mais (S-nice-to-have). */
        @JsonProperty("client_name")
        String clientName,

        @JsonProperty("service_item_ids")
        List<UUID> serviceItemIds,

        String status,

        @JsonProperty("expires_at")
        OffsetDateTime expiresAt,

        @JsonProperty("created_at")
        OffsetDateTime createdAt,

        @JsonProperty("resulting_attendance_id")
        UUID resultingAttendanceId,

        /** Dia e horario desejados pelo cliente (D6; Partner S26). null = "assim que abrir". */
        @JsonProperty("desired_time")
        OffsetDateTime desiredTime,

        /** O dia pedido nao estava liberado na agenda (solicitar-agendamento): o Partner mostra o dot e o aceite abre o dia. */
        @JsonProperty("agenda_requested")
        boolean agendaRequested,

        /** O cupom pedido junto (cupom-no-pedido-de-corte); null = sem cupom. */
        @JsonProperty("coupon_campaign_id")
        UUID couponCampaignId
) {
    public static CutRequestResponse fromEntity(CutRequest c) {
        return new CutRequestResponse(
                c.getId(),
                c.getProfessional() != null ? c.getProfessional().getId() : null,
                c.getEstablishment() != null ? c.getEstablishment().getId() : null,
                c.getClient() != null ? c.getClient().getId() : null,
                c.getClient() != null ? c.getClient().getFirstName() : null,
                c.getServiceItems() == null ? List.of()
                        : c.getServiceItems().stream().map(ServiceItem::getId).toList(),
                c.getStatus() != null ? c.getStatus().name() : null,
                c.getExpiresAt(),
                c.getCreatedAt(),
                c.getResultingAttendance() != null ? c.getResultingAttendance().getId() : null,
                c.getDesiredTime(),
                c.isAgendaRequested(),
                c.getCouponCampaignId()
        );
    }
}
