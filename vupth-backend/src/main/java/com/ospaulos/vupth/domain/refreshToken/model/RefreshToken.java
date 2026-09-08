package com.ospaulos.vupth.domain.refreshToken.model;

import com.ospaulos.vupth.domain.user.model.BaseUser;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

@Entity
@Table(name = "refresh_token")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RefreshToken {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true, length = 255)
    private String token;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "base_user_id", nullable = false)
    private BaseUser user;

    @Column(name = "expiry_date", nullable = false)
    private OffsetDateTime expiryDate;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "revoked_at")
    private OffsetDateTime revokedAt;

    /**
     * O token que substituiu este na rotacao. E o elo que sustenta a <b>janela de graca</b>: quando
     * duas chamadas do app disparam refresh ao mesmo tempo, a primeira rotaciona e as outras chegam
     * com um token ja revogado — sem este campo, a unica resposta possivel seria 401 e o usuario
     * seria deslogado por uma corrida que ele nao causou.
     *
     * <p><b>Por que o elo, e nao "qualquer token valido do usuario":</b> desde que uma conta pode ter
     * varias sessoes (um celular e um tablet, ou o Client e o Partner ao mesmo tempo), devolver um
     * token valido qualquer entregaria a sessao de OUTRO aparelho a quem perguntou. O elo devolve
     * exatamente o sucessor daquela sessao.</p>
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "replaced_by")
    private RefreshToken replacedBy;

    public boolean isExpired() {
        return OffsetDateTime.now(ZoneOffset.UTC).isAfter(expiryDate);
    }

    public boolean isRevoked() {
        return revokedAt != null;
    }

    public boolean isValid() {
        return !isExpired() && !isRevoked();
    }

    public void revoke() {
        this.revokedAt = OffsetDateTime.now();
    }
}
