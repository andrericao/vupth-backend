package com.ospaulos.vupth.domain.campaign.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Quem aceitou a campanha (uma vez por pessoa) e quando — a base do limite de pessoas e do relatorio. */
@Getter
@Entity
@NoArgsConstructor
@Table(name = "campaign_redemption",
        uniqueConstraints = @UniqueConstraint(name = "uk_campaign_redemption", columnNames = {"campaign_id", "user_id"}))
public class CampaignRedemption {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "campaign_id", nullable = false)
    private Campaign campaign;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "redeemed_at", nullable = false)
    private OffsetDateTime redeemedAt;

    public CampaignRedemption(Campaign campaign, UUID userId, OffsetDateTime redeemedAt) {
        this.id = UUID.randomUUID();
        this.campaign = campaign;
        this.userId = userId;
        this.redeemedAt = redeemedAt;
    }
}
