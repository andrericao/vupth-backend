package com.ospaulos.vupth.domain.referral.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.UUID;

/** O cliente ligado a quem o indicou (BAK-9): vale o primeiro codigo, que nao muda depois. */
@Getter
@Entity
@NoArgsConstructor
@Table(name = "referral")
public class Referral {

    @Id
    @Column(name = "client_id")
    private UUID clientId;

    @Column(name = "referrer_id", nullable = false)
    private UUID referrerId;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    /** A campanha do link por onde o cliente chegou; nulo = chegou sem campanha de adesao (registrado, mas nao rende premio). */
    @Column(name = "campaign_id")
    private UUID campaignId;

    public Referral(UUID clientId, UUID referrerId, OffsetDateTime createdAt) {
        this(clientId, referrerId, createdAt, null);
    }

    public Referral(UUID clientId, UUID referrerId, OffsetDateTime createdAt, UUID campaignId) {
        this.clientId = clientId;
        this.referrerId = referrerId;
        this.createdAt = createdAt;
        this.campaignId = campaignId;
    }
}
