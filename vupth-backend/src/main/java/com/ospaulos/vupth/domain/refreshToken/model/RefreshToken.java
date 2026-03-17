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
