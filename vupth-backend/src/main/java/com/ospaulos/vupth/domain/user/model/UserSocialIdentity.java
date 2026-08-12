package com.ospaulos.vupth.domain.user.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Vinculo entre uma conta nossa e uma identidade no provedor.
 *
 * <p>Tabela em vez de colunas no {@code base_user} porque a mesma pessoa pode
 * entrar por Google e por Apple — sao dois vinculos para um usuario.
 *
 * <p>A chave e o {@code providerUserId} (o {@code sub} do id-token), nao o
 * e-mail: o e-mail da Apple pode ser um relay que o usuario desativa depois, e
 * ai o vinculo por e-mail se perderia.
 */
@Entity
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(
        name = "user_social_identity",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_user_social_identity_provider_user",
                columnNames = {"provider", "provider_user_id"}
        )
)
public class UserSocialIdentity implements Serializable {

    private static final long serialVersionUID = 1L;

    @Id
    @GeneratedValue
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "base_user_id", nullable = false)
    private BaseUser user;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider", nullable = false, length = 16)
    private SocialProvider provider;

    @Column(name = "provider_user_id", nullable = false)
    private String providerUserId;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;
}
