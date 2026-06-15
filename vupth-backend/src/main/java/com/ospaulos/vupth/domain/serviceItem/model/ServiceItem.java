package com.ospaulos.vupth.domain.serviceItem.model;

import com.fasterxml.jackson.annotation.JsonBackReference;
import com.ospaulos.vupth.domain.serviceItem.valueObject.ServiceItemType;
import com.ospaulos.vupth.domain.professional.model.Professional;
import jakarta.persistence.*;
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

    @PrePersist
    public void prePersist() {
        if (estimatedMinutes == null) {
            estimatedMinutes = 30;
        }
    }
}
