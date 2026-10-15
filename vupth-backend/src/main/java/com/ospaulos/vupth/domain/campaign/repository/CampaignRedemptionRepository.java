package com.ospaulos.vupth.domain.campaign.repository;

import com.ospaulos.vupth.domain.campaign.model.CampaignRedemption;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface CampaignRedemptionRepository extends JpaRepository<CampaignRedemption, UUID> {

    long countByCampaign_Id(UUID campaignId);

    boolean existsByCampaign_IdAndUserId(UUID campaignId, UUID userId);

    @Query("SELECT r FROM CampaignRedemption r JOIN FETCH r.campaign WHERE r.userId = :userId ORDER BY r.redeemedAt DESC")
    List<CampaignRedemption> findByUserIdWithCampaign(@Param("userId") UUID userId);
}
