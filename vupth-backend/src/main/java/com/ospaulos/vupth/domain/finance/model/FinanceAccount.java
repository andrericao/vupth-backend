package com.ospaulos.vupth.domain.finance.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/** Uma conta por onde o dinheiro passa (ledger-por-conta): o Asaas, a conta PJ do app, outras no futuro; com a tarifa de Pix dela. */
@Getter
@Setter
@Entity
@NoArgsConstructor
@Table(name = "finance_account")
public class FinanceAccount {

    public static final String ASAAS = "ASAAS";
    public static final String PJ = "PJ";

    @Id
    private UUID id;

    @Column(name = "account_key", nullable = false, length = 20, unique = true)
    private String key;

    @Column(name = "name", nullable = false, length = 60)
    private String name;

    @Column(name = "provider", nullable = false, length = 30)
    private String provider;

    @Column(name = "pix_fee", nullable = false, precision = 10, scale = 2)
    private BigDecimal pixFee;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;
}
