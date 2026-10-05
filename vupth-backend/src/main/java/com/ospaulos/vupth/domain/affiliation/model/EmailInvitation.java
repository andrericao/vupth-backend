package com.ospaulos.vupth.domain.affiliation.model;

import com.ospaulos.vupth.domain.establishment.model.Establishment;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.UUID;

/**
 * Convite do dono para quem ainda NAO tem conta Vupth (convite-sem-conta, Partner S30). Guarda o
 * e-mail; quando alguem cria o perfil profissional com esse e-mail, vira um {@link AffiliationRequest}
 * {@code INVITATION} comum, que a pessoa aceita ou recusa no app como qualquer convite.
 */
@Getter
@Setter
@Entity
@NoArgsConstructor
@Table(name = "email_invitation")
public class EmailInvitation {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "establishment_id", nullable = false)
    private Establishment establishment;

    /** Sempre normalizado ({@link #normalize}): a conversao compara por igualdade. */
    @Column(name = "email", nullable = false, length = 150)
    private String email;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private EmailInvitationStatus status = EmailInvitationStatus.PENDING;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "converted_at")
    private OffsetDateTime convertedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "affiliation_request_id")
    private AffiliationRequest affiliationRequest;

    public EmailInvitation(Establishment establishment, String email) {
        this.establishment = establishment;
        this.email = normalize(email);
        this.status = EmailInvitationStatus.PENDING;
        this.createdAt = OffsetDateTime.now();
    }

    public static String normalize(String email) {
        return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }

    public void convertTo(AffiliationRequest invitation) {
        this.status = EmailInvitationStatus.CONVERTED;
        this.convertedAt = OffsetDateTime.now();
        this.affiliationRequest = invitation;
    }
}
