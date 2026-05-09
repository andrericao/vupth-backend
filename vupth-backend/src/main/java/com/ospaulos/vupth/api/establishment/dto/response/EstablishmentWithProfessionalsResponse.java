package com.ospaulos.vupth.api.establishment.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.ospaulos.vupth.domain.establishment.model.Establishment;
import com.ospaulos.vupth.domain.establishment.valueObject.ServiceType;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Snapshot completo do establishment incluindo todos os profissionais com suas filas.
 * Retornado por GET /establishment/id/{id}/professionals-with-queue
 */
public record EstablishmentWithProfessionalsResponse(

        UUID id,
        String name,

        @JsonProperty("url_image")
        String urlImage,

        Set<ServiceType> services,

        @JsonProperty("owner_id")
        UUID ownerId,

        @JsonProperty("open_status")
        String openStatus,

        @JsonProperty("total_in_queue")
        int totalInQueue,

        @JsonProperty("available_professionals")
        int availableProfessionals,

        List<ProfessionalWithQueueResponse> professionals

) {
    public static EstablishmentWithProfessionalsResponse fromEntity(
            Establishment establishment,
            List<ProfessionalWithQueueResponse> professionals) {

        int totalInQueue = professionals.stream()
                .mapToInt(ProfessionalWithQueueResponse::queueCount)
                .sum();

        int available = (int) professionals.stream()
                .filter(p -> Boolean.TRUE.equals(p.isAvailable()) && p.currentAttendance() == null)
                .count();

        return new EstablishmentWithProfessionalsResponse(
                establishment.getId(),
                establishment.getName(),
                establishment.getUrlImage(),
                establishment.getServices() != null ? Set.copyOf(establishment.getServices()) : Set.of(),
                establishment.getOwner().getId(),
                establishment.getOpenStatus().name(),
                totalInQueue,
                available,
                professionals
        );
    }
}
