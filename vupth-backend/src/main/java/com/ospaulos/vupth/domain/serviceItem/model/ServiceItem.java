package com.ospaulos.vupth.domain.serviceItem.model;

import com.fasterxml.jackson.annotation.JsonBackReference;
import com.ospaulos.vupth.domain.serviceItem.valueObject.PriceSplit;
import com.ospaulos.vupth.domain.serviceItem.valueObject.ServiceItemType;
import com.ospaulos.vupth.domain.professional.model.Professional;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Getter
@Setter
@NoArgsConstructor
public class ServiceItem {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @Enumerated(EnumType.STRING)
    private ServiceItemType serviceItemType;

    @Column(name = "custom_name")
    private String customName;

    @Column(name = "price", nullable = false)
    private BigDecimal price;

    // Total pago pelo cliente (preco + taxas, calculado no front). Fonte da verdade p/ o app cliente.
    @Column(name = "client_price")
    private BigDecimal clientPrice;

    // Se o servico usa a precificacao da plataforma (absorcao das Taxas Vupth).
    @Column(name = "uses_platform_pricing")
    private Boolean usesPlatformPricing;

    /**
     * Quanto o profissional recebe por este servico ("Voce recebe"), ja descontada a parte da taxa
     * que ele escolheu absorver. Null em servico legado: ninguem sabe quanto ele absorveu, e o valor
     * nao e estimado. So muda por {@link #reprice} / {@link #changeSplit}, que passam pela regra de
     * preco ({@link PriceSplit}).
     */
    @Setter(AccessLevel.NONE)
    @Column(name = "pro_receives", precision = 10, scale = 2)
    private BigDecimal proReceives;

    /**
     * O valor que o profissional digitou no cadastro — a base dos 6% (regra de 16/09). Com ele e o
     * {@code proReceives} a divisao do preco se reconstroi sem adivinhar. Null em servico legado.
     */
    @Setter(AccessLevel.NONE)
    @Column(name = "base_price", precision = 10, scale = 2)
    private BigDecimal basePrice;

    @Column(nullable = false)
    private Integer estimatedMinutes;

    /*
    @ManyToOne(fetch = FetchType.LAZY)
    @JsonBackReference
    @JoinColumn(name = "establishment_id", nullable = false)
    private Establishment establishment;
    */

    @ManyToOne(fetch = FetchType.LAZY)
    @JsonBackReference
    @JoinColumn(name = "professional_id", nullable = false)
    private Professional professional;

    @Column(name = "weekly_average_duration_in_minutes")
    private Integer weeklyAverageDurationInMinutes;

    /**
     * Agregados denormalizados de avaliacao (item #6). Recebem a nota so quando o atendimento
     * avaliado tinha exatamente 1 servico (este). A media por {@code serviceItemType} de um
     * profissional e o rollup desses ServiceItems. Media = {@code ratingSum / ratingCount}.
     */
    @Column(name = "rating_sum", nullable = false)
    private long ratingSum = 0L;

    @Column(name = "rating_count", nullable = false)
    private long ratingCount = 0L;

    @PrePersist
    public void prePersist() {
        if (estimatedMinutes == null) {
            estimatedMinutes = 30;
        }
    }

    /**
     * Troca o preco do cliente. Com {@code proReceives} e {@code basePrice} (os dois juntos), o trio passa
     * pela regra de preco. Sem eles, a divisao antiga so continua valendo se o preco nao mudou — preco novo
     * sem divisao volta o servico ao estado "a atualizar" em vez de guardar um liquido que pode nao valer.
     */
    public void reprice(BigDecimal newPrice, BigDecimal newProReceives, BigDecimal newBasePrice) {
        if (newProReceives != null || newBasePrice != null) {
            PriceSplit split = PriceSplit.of(newPrice, newProReceives, newBasePrice);
            this.price = split.clientPrice();
            this.proReceives = split.proReceives();
            this.basePrice = split.basePrice();
            return;
        }
        boolean samePrice = this.price != null && newPrice != null && this.price.compareTo(newPrice) == 0;
        this.price = newPrice;
        if (!samePrice) {
            this.proReceives = null;
            this.basePrice = null;
        }
    }

    /** Troca so a divisao (recebe + digitado), conferida contra o preco atual. */
    public void changeSplit(BigDecimal newProReceives, BigDecimal newBasePrice) {
        reprice(this.price, newProReceives, newBasePrice);
    }

    /**
     * A divisao do preco, ou {@code null} em servico legado — inclusive o que tem {@code proReceives}
     * gravado pela regra anterior (sem o digitado, a divisao nao se reconstroi). Le sem reconferir o X,90
     * ({@link PriceSplit#read}): o preco da regra de 16/09 segue valendo ate o profissional edita-lo.
     */
    @Transient
    public PriceSplit getPriceSplit() {
        return proReceives == null || basePrice == null ? null : PriceSplit.read(price, proReceives, basePrice);
    }

    /** Registra uma nota (item #6): soma no total e incrementa a contagem de votos. */
    public void addRating(int score) {
        this.ratingSum += score;
        this.ratingCount++;
    }

    /** Media de avaliacao (2 casas) ou {@code null} quando ainda nao ha votos. */
    @Transient
    public Double getRatingAverage() {
        if (ratingCount == 0) {
            return null;
        }
        return Math.round((double) ratingSum / ratingCount * 100.0) / 100.0;
    }
}
