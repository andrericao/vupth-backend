package com.ospaulos.vupth.domain.payment.repository;

import com.ospaulos.vupth.domain.payment.model.AppLedgerEntry;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public interface AppLedgerRepository extends JpaRepository<AppLedgerEntry, UUID> {

    boolean existsByAttendanceId(UUID attendanceId);

    List<AppLedgerEntry> findByAttendanceId(UUID attendanceId);

    /** O que o app ganhou no periodo (pelo dia em que o pagamento foi confirmado), do mais antigo ao mais novo. */
    List<AppLedgerEntry> findByCreatedAtGreaterThanEqualAndCreatedAtLessThanOrderByCreatedAtAsc(
            OffsetDateTime from, OffsetDateTime to);
}
