package com.ospaulos.vupth.domain.campaign.repository;

import com.ospaulos.vupth.domain.campaign.model.CouponCredit;
import com.ospaulos.vupth.domain.referral.repository.ReferralPayoutRow;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public interface CouponCreditRepository extends JpaRepository<CouponCredit, UUID> {

    boolean existsByAttendanceId(UUID attendanceId);

    List<CouponCredit> findByAttendanceIdIn(java.util.Collection<UUID> attendanceIds);

    List<CouponCredit> findByBeneficiaryIdOrderByCreatedAtDesc(UUID beneficiaryId);

    /** O que falta pagar, por campanha e por pessoa (soma com a bonificacao no painel de pagamentos). */
    @Query("""
        SELECT new com.ospaulos.vupth.domain.referral.repository.ReferralPayoutRow(
            c.campaignId, c.beneficiaryId, SUM(c.amount), COUNT(c))
        FROM CouponCredit c WHERE c.paidAt IS NULL
        GROUP BY c.campaignId, c.beneficiaryId
        """)
    List<ReferralPayoutRow> findUnpaidPayouts();

    @Modifying
    @Query("""
        UPDATE CouponCredit c SET c.paidAt = :now
        WHERE c.campaignId = :campaignId AND c.beneficiaryId = :userId AND c.paidAt IS NULL
        """)
    int markPaid(@Param("campaignId") UUID campaignId, @Param("userId") UUID userId, @Param("now") OffsetDateTime now);
}
