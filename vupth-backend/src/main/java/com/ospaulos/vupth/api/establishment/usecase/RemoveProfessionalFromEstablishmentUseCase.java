package com.ospaulos.vupth.api.establishment.usecase;

import com.ospaulos.vupth.domain.attendance.AttendanceStatus;
import com.ospaulos.vupth.domain.attendance.repository.AttendanceRepository;
import com.ospaulos.vupth.domain.establishment.exception.EstablishmentAccessDeniedException;
import com.ospaulos.vupth.domain.establishment.exception.EstablishmentNotFoundException;
import com.ospaulos.vupth.domain.establishment.model.Establishment;
import com.ospaulos.vupth.domain.establishment.repository.EstablishmentRepository;
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
public class RemoveProfessionalFromEstablishmentUseCase {

    private final EstablishmentRepository establishmentRepository;
    private final ProfessionalRepository professionalRepository;
    private final AttendanceRepository attendanceRepository;

    @Transactional
    public void execute(UUID ownerId, UUID establishmentId, UUID professionalId) {
        log.info("[RemoveProfessional] execute — owner={} establishment={} professional={}",
                ownerId, establishmentId, professionalId);

        Establishment establishment = establishmentRepository.findByIdEager(establishmentId)
                .orElseThrow(() -> new EstablishmentNotFoundException(establishmentId));

        if (establishment.getOwner() == null ||
                !establishment.getOwner().getId().equals(ownerId)) {
            throw new EstablishmentAccessDeniedException(
                    "You can only remove professionals from your own establishments.");
        }

        Professional professional = professionalRepository.findById(professionalId)
                .orElseThrow(() -> new ProfessionalNotFoundException(professionalId));

        if (professional.getEstablishment() == null ||
                !professional.getEstablishment().getId().equals(establishmentId)) {
            throw new ProfessionalNotFoundException(professionalId);
        }

        boolean hasActive = attendanceRepository.existsByProfessional_IdAndStatusIn(
                professionalId,
                List.of(AttendanceStatus.SCHEDULED, AttendanceStatus.IN_PROGRESS));

        if (hasActive) {
            throw new ProfessionalHasActiveAttendancesException(professionalId);
        }

        professional.setEstablishment(null);
        professionalRepository.save(professional);

        log.info("[RemoveProfessional] professional={} removed from establishment={}",
                professionalId, establishmentId);
    }
}
