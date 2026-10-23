package com.ospaulos.vupth.domain.referral.repository;

import com.ospaulos.vupth.domain.referral.model.ReferralCredit;
import com.ospaulos.vupth.domain.referral.model.ReferralShare;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface ReferralCreditRepository extends JpaRepository<ReferralCredit, UUID> {

    boolean existsByClientId(UUID clientId);

    /** Clientes que ja geraram credito na campanha (o limite de pessoas, "as X primeiras"). */
    @Query("SELECT COUNT(DISTINCT c.clientId) FROM ReferralCredit c WHERE c.campaignId = :campaignId")
    long countClientsByCampaign(@Param("campaignId") UUID campaignId);

    /** Clientes indicados por quem gerou o link que ja concluiram o corte com credito. */
    @Query("""
        SELECT COUNT(DISTINCT c.clientId) FROM ReferralCredit c
        WHERE c.beneficiaryId = :userId AND c.share IN :shares
        """)
    long countClientsByBeneficiaryAndShareIn(@Param("userId") UUID userId,
                                             @Param("shares") Collection<ReferralShare> shares);

    /** Clientes indicados por quem gerou o link, nesta campanha, que ja concluiram o corte com credito. */
    @Query("""
        SELECT COUNT(DISTINCT c.clientId) FROM ReferralCredit c
        WHERE c.beneficiaryId = :userId AND c.campaignId = :campaignId AND c.share IN :shares
        """)
    long countClientsByBeneficiaryAndCampaign(@Param("userId") UUID userId, @Param("campaignId") UUID campaignId,
                                              @Param("shares") Collection<ReferralShare> shares);

    /** O que a pessoa tem a receber: premios ja liberados pelo Andre e ainda nao pagos. */
    @Query("""
        SELECT COALESCE(SUM(c.amount), 0) FROM ReferralCredit c
        WHERE c.beneficiaryId = :userId AND c.paidAt IS NULL AND c.releasedAt IS NOT NULL
        """)
    BigDecimal sumUnpaidByBeneficiary(@Param("userId") UUID userId);

    /** Premios ganhos que esperam o Andre liberar. */
    @Query("""
        SELECT COALESCE(SUM(c.amount), 0) FROM ReferralCredit c
        WHERE c.beneficiaryId = :userId AND c.releasedAt IS NULL
        """)
    BigDecimal sumPendingReleaseByBeneficiary(@Param("userId") UUID userId);

    /** Tudo o que a campanha ja premiou (liberado ou nao), para o teto de gasto. */
    @Query("SELECT COALESCE(SUM(c.amount), 0) FROM ReferralCredit c WHERE c.campaignId = :campaignId")
    BigDecimal sumByCampaign(@Param("campaignId") UUID campaignId);

    /** O que falta pagar, por campanha e por pessoa. */
    @Query("""
        SELECT new com.ospaulos.vupth.domain.referral.repository.ReferralPayoutRow(
            c.campaignId, c.beneficiaryId, SUM(c.amount), COUNT(c))
        FROM ReferralCredit c WHERE c.paidAt IS NULL AND c.releasedAt IS NOT NULL
        GROUP BY c.campaignId, c.beneficiaryId
        """)
    List<ReferralPayoutRow> findUnpaidPayouts();

    /** Os premios travados, por campanha e por pessoa, para o Andre decidir o que liberar. */
    @Query("""
        SELECT new com.ospaulos.vupth.domain.referral.repository.ReferralPayoutRow(
            c.campaignId, c.beneficiaryId, SUM(c.amount), COUNT(c))
        FROM ReferralCredit c WHERE c.releasedAt IS NULL
        GROUP BY c.campaignId, c.beneficiaryId
        """)
    List<ReferralPayoutRow> findPendingRelease();

    /** Libera os premios travados da campanha, de todos; devolve quantos. */
    @Modifying
    @Query("UPDATE ReferralCredit c SET c.releasedAt = :now WHERE c.campaignId = :campaignId AND c.releasedAt IS NULL")
    int releaseAll(@Param("campaignId") UUID campaignId, @Param("now") OffsetDateTime now);

    /** Libera os premios travados da campanha de uma pessoa; devolve quantos. */
    @Modifying
    @Query("""
        UPDATE ReferralCredit c SET c.releasedAt = :now
        WHERE c.campaignId = :campaignId AND c.releasedAt IS NULL AND c.beneficiaryId = :userId
        """)
    int releaseFor(@Param("campaignId") UUID campaignId, @Param("userId") UUID userId, @Param("now") OffsetDateTime now);

    /** Marca como pagos os creditos em aberto da pessoa na campanha; devolve quantos. */
    @Modifying
    @Query("""
        UPDATE ReferralCredit c SET c.paidAt = :now
        WHERE c.campaignId = :campaignId AND c.beneficiaryId = :userId AND c.paidAt IS NULL AND c.releasedAt IS NOT NULL
        """)
    int markPaid(@Param("campaignId") UUID campaignId, @Param("userId") UUID userId, @Param("now") OffsetDateTime now);

    /** A bonificacao por link que o corte gerou (premio do indicador), liberada ou nao; para o relatorio do Asaas. */
    @Query("SELECT COALESCE(SUM(c.amount), 0) FROM ReferralCredit c WHERE c.attendanceId = :attendanceId")
    BigDecimal sumByAttendance(@Param("attendanceId") UUID attendanceId);
}
