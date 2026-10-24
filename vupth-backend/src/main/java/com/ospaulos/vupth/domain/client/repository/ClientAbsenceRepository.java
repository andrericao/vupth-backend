package com.ospaulos.vupth.domain.client.repository;

import com.ospaulos.vupth.domain.client.model.ClientAbsence;
import com.ospaulos.vupth.domain.client.model.ClientAbsenceType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.OffsetDateTime;
import java.util.UUID;

public interface ClientAbsenceRepository extends JpaRepository<ClientAbsence, UUID> {

    boolean existsByAttendanceIdAndType(UUID attendanceId, ClientAbsenceType type);

    /** As ocorrencias do cliente desde {@code since}. */
    long countByClientIdAndCreatedAtAfter(UUID clientId, OffsetDateTime since);
}
