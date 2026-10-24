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

    /*
     * Bonificacao por link (REFERRAL_BONUS, DEC-70/73): nenhum valor fixo no codigo; o Andre preenche no painel.
     * A divisao vale para a loja so com dono (o dono gera o link, os profissionais atendem).
     */

    /** Parte do valor ao profissional que atendeu (%). */
    @Column(name = "referral_professional_percent", precision = 5, scale = 2)
    private BigDecimal referralProfessionalPercent;

    /** Parte do valor ao dono que gerou o link (%). Com a do profissional, soma 100. */
    @Column(name = "referral_owner_percent", precision = 5, scale = 2)
    private BigDecimal referralOwnerPercent;

    @Enumerated(EnumType.STRING)
    @Column(name = "referral_scope", length = 25)
    private ReferralScope referralScope;

    @Enumerated(EnumType.STRING)
    @Column(name = "payout_period", length = 10)
    private PayoutPeriod payoutPeriod;

    /** Dia do pagamento: da semana (1-7) na semanal, do mes (1-28) na mensal. */
    @Column(name = "payout_day")
    private Integer payoutDay;

    /** Base do desconto do cupom (cupom-ativado-em-configuracoes): o preco do cliente ou o que o profissional recebe. */
    @Enumerated(EnumType.STRING)
    @Column(name = "benefit_base", length = 12, nullable = false)
    private BenefitBase benefitBase = BenefitBase.WITH_FEE;

    /** Teto de gasto da campanha de link (compartilhe-e-ganhe): a soma dos premios nao passa dele; nulo = sem teto. */
    @Column(name = "max_total_cost", precision = 10, scale = 2)
    private BigDecimal maxTotalCost;

    /** Passou do prazo ({@code ends_at}) em {@code now}: encerrada para todos (DEC-124). */
    public boolean hasEnded(OffsetDateTime now) {
        return !now.isBefore(endsAt);
    }

    /** Ligada e dentro da janela em {@code now}. */
    public boolean isRunning(OffsetDateTime now) {
        return active && !now.isBefore(startsAt) && now.isBefore(endsAt);
    }
}
