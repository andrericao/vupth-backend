package com.ospaulos.vupth.api.professional.usecase;

import com.ospaulos.vupth.domain.attendance.AttendanceStatus;
import com.ospaulos.vupth.domain.attendance.repository.AttendanceRepository;
import com.ospaulos.vupth.domain.establishment.service.EstablishmentOpenStatusService;
import com.ospaulos.vupth.domain.professional.exception.ProfessionalHasActiveAttendancesException;
import com.ospaulos.vupth.domain.professional.exception.ProfessionalNotFoundException;
import com.ospaulos.vupth.domain.professional.model.Professional;
import com.ospaulos.vupth.domain.professional.repository.ProfessionalRepository;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class LeaveEstablishmentUseCase {

    private final ProfessionalRepository professionalRepository;
    private final AttendanceRepository attendanceRepository;
    private final EstablishmentOpenStatusService openStatusService;

    /**
     * @return o id do establishment deixado (para o broadcast pos-commit), ou {@code null}
     *         se o profissional ja estava sem vinculo (no-op).
     */
    @Transactional
    public UUID execute(UUID professionalId) {
        log.info("[LeaveEstablishment] execute — professional={}", professionalId);

        Professional professional = professionalRepository.findById(professionalId)
                .orElseThrow(() -> new ProfessionalNotFoundException(professionalId));

        if (professional.getEstablishment() == null) {
            log.info("[LeaveEstablishment] professional={} already has no establishment", professionalId);
            return null;
        }

        boolean hasActive = attendanceRepository.existsByProfessional_IdAndStatusIn(
                professionalId,
                List.of(AttendanceStatus.SCHEDULED, AttendanceStatus.IN_PROGRESS, AttendanceStatus.PAUSED));

        if (hasActive) {
            throw new ProfessionalHasActiveAttendancesException(professionalId);
        }

        UUID establishmentId = professional.getEstablishment().getId();
        professional.setEstablishment(null);
        professionalRepository.save(professional);

        // S1 fatia 2 — se saiu o ultimo WORKING/ON_BREAK, a loja fecha (recalculo atomico).
        // Saida OFF nao muda a presenca -> nao recalcula (preserva override manual do hibrido).
        if (professional.getWorkStatus() != null && professional.getWorkStatus().countsAsOpen()) {
            openStatusService.deriveFromProfessionals(establishmentId);
        }

        log.info("[LeaveEstablishment] professional={} left establishment={}", professionalId, establishmentId);
        return establishmentId;
    }
}
