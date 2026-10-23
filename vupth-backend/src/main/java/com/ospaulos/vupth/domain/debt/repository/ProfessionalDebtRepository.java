package com.ospaulos.vupth.domain.debt.repository;

import com.ospaulos.vupth.domain.debt.model.DebtStatus;
import com.ospaulos.vupth.domain.debt.model.ProfessionalDebt;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public interface ProfessionalDebtRepository extends JpaRepository<ProfessionalDebt, UUID> {

    boolean existsByAttendanceId(UUID attendanceId);

    List<ProfessionalDebt> findByProfessionalIdOrderByCreatedAtDesc(UUID professionalId);

    List<ProfessionalDebt> findByProfessionalIdAndStatusAndSimulated(UUID professionalId, DebtStatus status, boolean simulated);

    /** O que cada profissional deve (ou, com {@code simulated}, o que deveria no teste), so as abertas. */
    @Query("""
        SELECT d.professionalId, SUM(d.amount) FROM ProfessionalDebt d
        WHERE d.status = com.ospaulos.vupth.domain.debt.model.DebtStatus.OPEN AND d.simulated = :simulated
        GROUP BY d.professionalId ORDER BY SUM(d.amount) DESC
        """)
    List<Object[]> openByProfessional(@Param("simulated") boolean simulated);

    @Query("""
        SELECT COALESCE(SUM(d.amount), 0) FROM ProfessionalDebt d
        WHERE d.professionalId = :professionalId AND d.status = com.ospaulos.vupth.domain.debt.model.DebtStatus.OPEN
          AND d.simulated = :simulated
        """)
    BigDecimal sumOpen(@Param("professionalId") UUID professionalId, @Param("simulated") boolean simulated);
}
