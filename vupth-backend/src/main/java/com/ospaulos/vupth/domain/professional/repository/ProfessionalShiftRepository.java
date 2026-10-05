package com.ospaulos.vupth.domain.professional.repository;

import com.ospaulos.vupth.domain.professional.model.ProfessionalShift;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public interface ProfessionalShiftRepository extends JpaRepository<ProfessionalShift, UUID> {

    List<ProfessionalShift> findByProfessionalIdAndEndedAtIsNull(UUID professionalId);

    /** Turnos que COMECARAM no periodo [from, to), em ordem de inicio. */
    List<ProfessionalShift> findByProfessionalIdAndStartedAtGreaterThanEqualAndStartedAtLessThanOrderByStartedAtAsc(
            UUID professionalId, OffsetDateTime from, OffsetDateTime to);

    List<ProfessionalShift> findByEstablishmentIdAndStartedAtGreaterThanEqualAndStartedAtLessThanOrderByStartedAtAsc(
            UUID establishmentId, OffsetDateTime from, OffsetDateTime to);
}
