package com.ospaulos.vupth.api.professional.usecase;

import com.ospaulos.vupth.domain.attendance.AttendanceStatus;
import com.ospaulos.vupth.domain.attendance.repository.AttendanceRepository;
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

    @Transactional
    public void execute(UUID professionalId) {
        log.info("[LeaveEstablishment] execute — professional={}", professionalId);

        Professional professional = professionalRepository.findById(professionalId)
                .orElseThrow(() -> new ProfessionalNotFoundException(professionalId));

        if (professional.getEstablishment() == null) {
            log.info("[LeaveEstablishment] professional={} already has no establishment", professionalId);
            return;
        }

        boolean hasActive = attendanceRepository.existsByProfessional_IdAndStatusIn(
                professionalId,
                List.of(AttendanceStatus.SCHEDULED, AttendanceStatus.IN_PROGRESS));

        if (hasActive) {
            throw new ProfessionalHasActiveAttendancesException(professionalId);
        }

        UUID establishmentId = professional.getEstablishment().getId();
        professional.setEstablishment(null);
        professionalRepository.save(professional);

        log.info("[LeaveEstablishment] professional={} left establishment={}", professionalId, establishmentId);
    }
}
