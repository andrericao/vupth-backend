package com.ospaulos.vupth.api.professional.usecase;

import com.ospaulos.vupth.api.professional.dto.response.ProfessionalQuickCreateResponse;
import com.ospaulos.vupth.domain.attendance.AttendanceStatus;
import com.ospaulos.vupth.domain.attendance.repository.AttendanceRepository;
import com.ospaulos.vupth.domain.professional.exception.OperationModeLockedException;
import com.ospaulos.vupth.domain.professional.exception.ProfessionalNotFoundException;
import com.ospaulos.vupth.domain.professional.model.Professional;
import com.ospaulos.vupth.domain.professional.repository.ProfessionalRepository;
import com.ospaulos.vupth.domain.professional.valueObject.OperationMode;
import com.ospaulos.vupth.domain.shared.BusinessClock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Troca o modo de funcionamento do profissional — fila ou so agenda (D9, por profissional; Andre 25/09).
 *
 * <p>So no inicio do expediente, <b>antes do primeiro cliente na fila de hoje</b>: com alguem
 * esperando, em atendimento ou ja atendido hoje, o modo esta travado ({@code 409 OPERATION_MODE_LOCKED})
 * — trocar no meio do dia mudaria a regra para quem ja esta la. Agendamentos futuros nao travam: o
 * agendado vale nos dois modos. Idempotente.
 *
 * <p>HTTP: {@code PATCH /professional/me/operation-mode}, Auth: ROLE_PROFESSIONAL.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class UpdateOperationModeUseCase {

    /** Quem ja passou pela fila hoje: esperando, atrasado, em atendimento, pausado ou atendido. */
    private static final List<AttendanceStatus> QUEUE_STARTED = List.of(
            AttendanceStatus.SCHEDULED, AttendanceStatus.LATE, AttendanceStatus.IN_PROGRESS,
            AttendanceStatus.PAUSED, AttendanceStatus.FINISHED);

    private final ProfessionalRepository professionalRepository;
    private final AttendanceRepository attendanceRepository;

    public ProfessionalQuickCreateResponse execute(UUID professionalId, OperationMode mode) {
        Professional professional = professionalRepository.findByIdWithBaseUser(professionalId)
                .orElseThrow(() -> new ProfessionalNotFoundException(professionalId));

        if (professional.getOperationMode() != mode) {
            LocalDate today = BusinessClock.today();
            boolean queueStartedToday = attendanceRepository.existsByProfessionalIdAndStatusInAndScheduledTimeBetween(
                    professionalId, QUEUE_STARTED,
                    BusinessClock.startOfDay(today), BusinessClock.endOfDayExclusive(today));
            if (queueStartedToday) {
                throw new OperationModeLockedException();
            }
            professional.setOperationMode(mode);
            professionalRepository.save(professional);
            log.info("[OperationMode] professional={} agora trabalha em {}", professionalId, mode);
        }

        return new ProfessionalQuickCreateResponse(
                professional.getId(),
                professional.getBaseUser().getFirstName(),
                professional.getSpecialty(),
                professional.getBaseUser().getNickname(),
                professional.getEstablishment() != null ? professional.getEstablishment().getId() : null,
                professional.getEstablishment() != null ? professional.getEstablishment().getName() : null,
                professional.getIsAvailable(),
                professional.getWorkStatus(),
                professional.getAcceptingNew(),
                professional.getOperationMode(),
                null
        );
    }
}
