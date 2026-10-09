package com.ospaulos.vupth.api.professional.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.ospaulos.vupth.domain.professional.model.Professional;
import com.ospaulos.vupth.domain.professional.valueObject.ProfessionalStatus;

import java.util.UUID;

/**
 * Retorno do cadastro simplificado de profissional.
 * The {@code id} is fixed and permanent — it never changes, even if specialty,
 * photo or display name are updated later.
 */
public record ProfessionalQuickCreateResponse(

        UUID id,
        String name,
        String specialty,
        String nickname,

        @JsonProperty("establishment_id")
        UUID establishmentId,

        @JsonProperty("establishment_name")
        String establishmentName,

        @JsonProperty("is_available")
        Boolean isAvailable,

        @JsonProperty("work_status")
        ProfessionalStatus workStatus,

        // S1 fatia 3 — "fechar fila": false = fila fechada p/ novos clientes (drenagem)
        @JsonProperty("accepting_new")
        Boolean acceptingNew,

        // D9 (25/09): QUEUE ou AGENDA_ONLY
        @JsonProperty("operation_mode")
        com.ospaulos.vupth.domain.professional.valueObject.OperationMode operationMode,

        String message,

        // Contato exposto ao cliente (PATCH /professional/me); null = nao expoe
        @JsonProperty("contact_phone")
        String contactPhone,

        // Interruptor "expor a posicao na fila" (decisao de 05/09); null onde nao foi lido
        @JsonProperty("show_position")
        Boolean showPosition,

        // desativar-conta (S42): ACTIVE | DEACTIVATED da loja onde trabalha; null sem loja ou onde nao foi lido
        @JsonProperty("establishment_status")
        String establishmentStatus
) {
    public ProfessionalQuickCreateResponse(
            UUID id, String name, String specialty, String nickname, UUID establishmentId,
            String establishmentName, Boolean isAvailable, ProfessionalStatus workStatus,
            Boolean acceptingNew,
            com.ospaulos.vupth.domain.professional.valueObject.OperationMode operationMode,
            String message, String contactPhone, Boolean showPosition) {
        this(id, name, specialty, nickname, establishmentId, establishmentName, isAvailable,
                workStatus, acceptingNew, operationMode, message, contactPhone, showPosition, null);
    }

    public ProfessionalQuickCreateResponse(
            UUID id, String name, String specialty, String nickname, UUID establishmentId,
            String establishmentName, Boolean isAvailable, ProfessionalStatus workStatus,
            Boolean acceptingNew,
            com.ospaulos.vupth.domain.professional.valueObject.OperationMode operationMode,
            String message) {
        this(id, name, specialty, nickname, establishmentId, establishmentName, isAvailable,
                workStatus, acceptingNew, operationMode, message, null, null, null);
    }

    public static ProfessionalQuickCreateResponse fromEntity(Professional p) {
        return new ProfessionalQuickCreateResponse(
                p.getId(),
                p.getBaseUser().getFirstName(),
                p.getSpecialty(),
                p.getBaseUser().getNickname(),
                p.getEstablishment() != null ? p.getEstablishment().getId() : null,
                p.getEstablishment() != null ? p.getEstablishment().getName() : null,
                p.getIsAvailable(),
                p.getWorkStatus(),
                p.getAcceptingNew(),
                p.getOperationMode(),
                "Professional created! Your ID is permanent. Fill in the rest whenever you want."
        );
    }
}
