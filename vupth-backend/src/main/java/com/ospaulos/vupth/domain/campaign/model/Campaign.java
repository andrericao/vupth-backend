package com.ospaulos.vupth.domain.campaign.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Campanha (promocao-de-lancamento v2, DEC-57/61/64). O nucleo: quem cria e o Andre, por migracao, ate o
 * painel existir. A publica aparece em {@code GET /campaigns/active}; a privada tem cupom ({@code code}),
 * limite de pessoas e o perfil de quem aceita. O beneficio (bonus, isencao, desconto) e aplicado nos
 * proximos itens; aqui ficam a regra, a janela e quem aceitou.
 */
@Getter
@Setter
@Entity
@NoArgsConstructor
@Table(name = "campaign")
public class Campaign {

    @Id
    private UUID id;

    /** O cupom que se digita (privada); na publica pode ser nulo. Unico, sem diferenciar maiuscula. */
    @Column(name = "code", length = 40)
    private String code;

    @Column(name = "name", length = 80, nullable = false)
    private String name;

    @Column(name = "description", length = 300)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", length = 20, nullable = false)
    private CampaignType type;

    @Enumerated(EnumType.STRING)
    @Column(name = "visibility", length = 10, nullable = false)
    private CampaignVisibility visibility;

    /** Quem pode aceitar; nulo = qualquer perfil. */
    @Enumerated(EnumType.STRING)
    @Column(name = "audience", length = 15)
    private CampaignAudience audience;

    @Column(name = "value_amount", precision = 10, scale = 2)
    private BigDecimal valueAmount;

    @Column(name = "value_percent", precision = 5, scale = 2)
    private BigDecimal valuePercent;

    @Column(name = "starts_at", nullable = false)
    private OffsetDateTime startsAt;

    @Column(name = "ends_at", nullable = false)
    private OffsetDateTime endsAt;

    /** Dias em que o beneficio vale (ex.: "TUESDAY,WEDNESDAY,THURSDAY"); nulo = todos. */
    @Column(name = "weekdays", length = 80)
    private String weekdays;

    /** Teto de pessoas que aceitam; nulo = sem teto. */
    @Column(name = "max_redemptions")
    private Integer maxRedemptions;

    /** So cliente sem corte concluido antes (DEC-61: "so cliente NOVO conta"). */
    @Column(name = "new_clients_only", nullable = false)
    private boolean newClientsOnly;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    /** Ligada e dentro da janela em {@code now}. */
    public boolean isRunning(OffsetDateTime now) {
        return active && !now.isBefore(startsAt) && now.isBefore(endsAt);
    }
}
