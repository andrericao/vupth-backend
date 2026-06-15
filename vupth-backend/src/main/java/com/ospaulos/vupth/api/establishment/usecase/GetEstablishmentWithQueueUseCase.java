package com.ospaulos.vupth.api.establishment.usecase;

import com.ospaulos.vupth.api.establishment.dto.response.EstablishmentWithProfessionalsResponse;
import com.ospaulos.vupth.api.establishment.dto.response.ProfessionalWithQueueResponse;
import com.ospaulos.vupth.domain.attendance.AttendanceStatus;
import com.ospaulos.vupth.domain.attendance.model.Attendance;
import com.ospaulos.vupth.domain.attendance.repository.AttendanceRepository;
import com.ospaulos.vupth.domain.establishment.model.Establishment;
import com.ospaulos.vupth.domain.establishment.repository.EstablishmentRepository;
import com.ospaulos.vupth.domain.professional.model.Professional;
import com.ospaulos.vupth.domain.professional.repository.ProfessionalRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Retorna o snapshot completo de um establishment:
 * - dados do establishment
 * - lista de profissionais vinculados
 * - para cada profissional: attendance em andamento + waiting queue (SCHEDULED)
 *
 * Endpoint: GET /establishment/id/{id}/professionals-with-queue
 * Access: public (GET /establishment/** is permitAll in SecurityConfig)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GetEstablishmentWithQueueUseCase {

    private final EstablishmentRepository establishmentRepository;
    private final ProfessionalRepository professionalRepository;
    private final AttendanceRepository attendanceRepository;

    @Transactional(readOnly = true)
    public EstablishmentWithProfessionalsResponse execute(UUID establishmentId) {

        log.info("GetEstablishmentWithQueueUseCase — establishmentId={}", establishmentId);

        Establishment establishment = establishmentRepository.findByIdEager(establishmentId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Establishment not found: " + establishmentId));

        // Item #31 (2a metade): profissional sem servico nao aparece para o cliente,
        // mesmo com a loja aberta. So entra na fila quem tem >= 1 servico.
        List<Professional> professionals = professionalRepository
                .findByEstablishmentIdWithServices(establishmentId).stream()
                .filter(prof -> prof.getServices() != null && !prof.getServices().isEmpty())
                .toList();

        List<ProfessionalWithQueueResponse> professionalDtos = professionals.stream()
                .map(prof -> buildProfessionalSnapshot(prof, establishmentId))
                .toList();

        log.info("Establishment {} has {} professionals, total queue={}",
                establishmentId, professionals.size(),
                professionalDtos.stream().mapToInt(ProfessionalWithQueueResponse::queueCount).sum());

        return EstablishmentWithProfessionalsResponse.fromEntity(establishment, professionalDtos);
    }

    private ProfessionalWithQueueResponse buildProfessionalSnapshot(
            Professional professional, UUID establishmentId) {

        UUID profId = professional.getId();

        // Atendimento IN_PROGRESS (max 1 por profissional)
        Optional<Attendance> current = attendanceRepository
                .findCurrentAttendanceByProfessional(profId, AttendanceStatus.IN_PROGRESS);

        // Fila de espera (SCHEDULED), ordenada por scheduledTime
        List<Attendance> waiting = attendanceRepository
                .findWaitingQueueByProfessionalAndEstablishment(profId, establishmentId);

        return ProfessionalWithQueueResponse.fromEntity(
                professional,
                current.orElse(null),
                waiting
        );
    }
}
