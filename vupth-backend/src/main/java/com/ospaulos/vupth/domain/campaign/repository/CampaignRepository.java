package com.ospaulos.vupth.domain.campaign.repository;

import com.ospaulos.vupth.domain.campaign.model.Campaign;
import com.ospaulos.vupth.domain.campaign.model.CampaignType;
import com.ospaulos.vupth.domain.campaign.model.CampaignVisibility;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CampaignRepository extends JpaRepository<Campaign, UUID> {

    Optional<Campaign> findByCodeIgnoreCase(String code);

    /** Ligadas, da visibilidade pedida e dentro da janela em {@code now}, as que acabam primeiro antes. */
    @Query("""
        SELECT c FROM Campaign c
        WHERE c.active = true AND c.visibility = :visibility
          AND c.startsAt <= :now AND c.endsAt > :now
        ORDER BY c.endsAt ASC
        """)
    List<Campaign> findRunning(@Param("visibility") CampaignVisibility visibility, @Param("now") OffsetDateTime now);

    /** Ligadas, do tipo pedido e dentro da janela em {@code now}, as que acabam primeiro antes. */
    @Query("""
        SELECT c FROM Campaign c
        WHERE c.active = true AND c.type = :type
          AND c.startsAt <= :now AND c.endsAt > :now
        ORDER BY c.endsAt ASC
        """)
    List<Campaign> findRunningOfType(@Param("type") CampaignType type, @Param("now") OffsetDateTime now);
}
