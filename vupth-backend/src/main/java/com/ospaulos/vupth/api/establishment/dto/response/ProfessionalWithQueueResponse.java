package com.ospaulos.vupth.api.establishment.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.ospaulos.vupth.api.attendance.dto.response.AttendanceInQueueResponse;
import com.ospaulos.vupth.domain.attendance.model.Attendance;
import com.ospaulos.vupth.domain.professional.model.Professional;
import com.ospaulos.vupth.domain.professional.model.ProfessionalInterval;
import com.ospaulos.vupth.domain.professional.valueObject.ProfessionalAvailability;
import com.ospaulos.vupth.domain.professional.valueObject.ProfessionalStatus;
import com.ospaulos.vupth.domain.queue.service.QueueUtils;
import com.ospaulos.vupth.domain.serviceItem.model.ServiceItem;
import com.ospaulos.vupth.domain.serviceItem.valueObject.ServiceItemType;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Representa um profissional com a fila ao vivo embutida.
 * Usado em GET /establishment/id/{id}/professionals-with-queue
 */
public record ProfessionalWithQueueResponse(

        UUID id,
        String name,
        String specialty,
        String nickname,

        @JsonProperty("is_available")
        Boolean isAvailable,

        // S1 fatia 2 — status de trabalho do profissional (WORKING/ON_BREAK/OFF); base do
        // open_status derivado da loja. Pedido explicito do Partner (handoff S1).
        @JsonProperty("work_status")
        ProfessionalStatus workStatus,

        // S1 fatia 3 — "fechar fila": false = fila fechada p/ novos clientes (drenando os atuais)
        @JsonProperty("accepting_new")
        Boolean acceptingNew,

        // S1 fatia 4 — rotulo do CLIENTE, derivado de work_status + accepting_new (read-only,
        // nunca persistido): DISPONIVEL / ENCERRANDO / INDISPONIVEL.
        @JsonProperty("availability")
        ProfessionalAvailability availability,

        @JsonProperty("current_attendance")
        AttendanceInQueueResponse currentAttendance,

        @JsonProperty("waiting_list")
        List<AttendanceInQueueResponse> waitingList,

        @JsonProperty("queue_count")
        int queueCount,

        @JsonProperty("estimated_wait_minutes")
        int estimatedWaitMinutes,

        // Rating denormalizado do profissional (item #6). Media (2 casas) null quando sem votos.
        @JsonProperty("rating_average")
        Double ratingAverage,

        @JsonProperty("rating_count")
        long ratingCount,

        @JsonProperty("service_items")
        List<ServiceItemSummary> serviceItems,

        // S22: foto de perfil (URL publica no R2); null sem foto.
        @JsonProperty("profile_image_url")
        String profileImageUrl,

        // D9 (25/09): AGENDA_ONLY = o app desabilita "entrar na fila" e oferece so agendar
        @JsonProperty("operation_mode")
        com.ospaulos.vupth.domain.professional.valueObject.OperationMode operationMode

) {

    public record ServiceItemSummary(
            UUID id,
            ServiceItemType serviceItemType,
            String customName,
            BigDecimal price,
            Integer estimatedMinutes,

            // Rating denormalizado por servico (item #6). Media (2 casas) null quando sem votos.
            @JsonProperty("rating_average")
            Double ratingAverage,

            @JsonProperty("rating_count")
            long ratingCount
    ) {
        public static ServiceItemSummary fromEntity(ServiceItem item) {
            return new ServiceItemSummary(
                    item.getId(),
                    item.getServiceItemType(),
                    item.getCustomName(),
                    item.getPrice(),
                    item.getEstimatedMinutes(),
                    item.getRatingAverage(),
                    item.getRatingCount()
            );
        }
    }

    public static ProfessionalWithQueueResponse fromEntity(
            Professional professional,
            Attendance currentAttendance,
            List<Attendance> waitingList) {
        return fromEntity(professional, currentAttendance, waitingList, List.of(), OffsetDateTime.now());
    }

    public static ProfessionalWithQueueResponse fromEntity(
            Professional professional,
            Attendance currentAttendance,
            List<Attendance> waitingList,
            List<ProfessionalInterval> intervals,
            OffsetDateTime now) {

        AttendanceInQueueResponse currentDto = currentAttendance != null
                ? AttendanceInQueueResponse.fromEntity(currentAttendance)
                : null;

        List<AttendanceInQueueResponse> waitingDtos = waitingList.stream()
                .map(AttendanceInQueueResponse::fromEntity)
                .toList();

        // Tempo estimado = espera da fila inteira (meal-aware, item #37 pre-req: inclui as
        // refeicoes previstas/iniciadas do profissional via QueueUtils) + restante do em progresso.
        // A conta mora no QueueUtils porque a LISTAGEM publica calcula o mesmo numero por outro
        // caminho (B10 — resumo em lote, sem passar por este snapshot): fonte unica ou as duas
        // telas divergem.
        int estimatedWait = QueueUtils.professionalEta(waitingList, intervals, currentAttendance, now);

        List<ServiceItemSummary> serviceItemDtos = professional.getServices().stream()
                .map(ServiceItemSummary::fromEntity)
                .toList();

        return new ProfessionalWithQueueResponse(
                professional.getId(),
                professional.getName(),
                professional.getSpecialty(),
                professional.getBaseUser() != null ? professional.getBaseUser().getNickname() : null,
                professional.getIsAvailable(),
                professional.getWorkStatus(),
                professional.getAcceptingNew(),
                ProfessionalAvailability.derive(
                        professional.getWorkStatus(), professional.getAcceptingNew()),
                currentDto,
                waitingDtos,
                waitingList.size(),
                estimatedWait,
                professional.getRatingAverage(),
                professional.getRatingCount(),
                serviceItemDtos,
                professional.getBaseUser() != null ? professional.getBaseUser().getProfileImageUrl() : null,
                professional.getOperationMode()
        );
    }
}
