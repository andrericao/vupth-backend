package com.ospaulos.vupth.domain.payment.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A conta de recebimento do profissional no gateway (gateway-de-pagamento, fatia 1): a subconta do Asaas, o modo em que nasceu e o
 * aceite dos termos (versao, data, IP; {@code test} enquanto o texto for rascunho). Nao guarda a chave de API da subconta.
 */
@Getter
@Entity
@NoArgsConstructor
@Table(name = "payment_account")
public class PaymentAccount {

    public static final String ACTIVE = "ACTIVE";

    @Id
    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "provider", nullable = false, length = 20)
    private String provider = "ASAAS";

    @Column(name = "provider_account_id", nullable = false, length = 60)
    private String providerAccountId;

    @Column(name = "wallet_id", nullable = false, length = 60)
    private String walletId;

    @Column(name = "mode", nullable = false, length = 10)
    private String mode;

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "accept_version", nullable = false, length = 40)
    private String acceptVersion;

    @Column(name = "accepted_at", nullable = false)
    private OffsetDateTime acceptedAt;

    @Column(name = "accepted_ip", length = 60)
    private String acceptedIp;

    @Column(name = "test", nullable = false)
    private boolean test;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    public PaymentAccount(UUID userId, String providerAccountId, String walletId, String mode, String acceptVersion,
                          OffsetDateTime acceptedAt, String acceptedIp, boolean test) {
        this.userId = userId;
        this.providerAccountId = providerAccountId;
        this.walletId = walletId;
        this.mode = mode;
        this.status = ACTIVE;
        this.acceptVersion = acceptVersion;
        this.acceptedAt = acceptedAt;
        this.acceptedIp = acceptedIp;
        this.test = test;
        this.createdAt = acceptedAt;
    }
}
