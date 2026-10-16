package com.ospaulos.vupth.domain.referral.repository;

import com.ospaulos.vupth.domain.referral.model.ReferralCredit;
import com.ospaulos.vupth.domain.referral.model.ReferralShare;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.Collection;
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

    /** O que a pessoa tem a receber (creditos ainda nao pagos). */
    @Query("""
        SELECT COALESCE(SUM(c.amount), 0) FROM ReferralCredit c
        WHERE c.beneficiaryId = :userId AND c.paidAt IS NULL
        """)
    BigDecimal sumUnpaidByBeneficiary(@Param("userId") UUID userId);
}
