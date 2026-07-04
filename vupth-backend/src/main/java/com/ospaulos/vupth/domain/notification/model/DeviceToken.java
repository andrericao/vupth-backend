package com.ospaulos.vupth.domain.notification.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Device token (Expo Push Token) de um usuario para notificacoes push (backlog #1).
 *
 * <p>Um usuario pode ter varios devices; cada {@code token} e unico (identifica o aparelho). O front
 * registra no login/abertura (`POST /devices`) e remove no logout (`DELETE /devices/{token}`). O
 * disparo de push resolve os tokens do usuario destino e envia via Expo. Vale para qualquer papel
 * (CLIENT recebe "sua vez"/"proximo"; PROFESSIONAL/OWNER podem receber eventos futuros).</p>
 */
@Getter
@Setter
@Entity
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "device_token", indexes = {
        @Index(name = "idx_device_token_user", columnList = "user_id")
}, uniqueConstraints = {
        @UniqueConstraint(name = "uq_device_token_token", columnNames = "token")
})
public class DeviceToken implements Serializable {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** Dono do device (id do {@code base_user}). */
    @Column(name = "user_id", nullable = false)
    private UUID userId;

    /** Expo Push Token do aparelho (unico). */
    @Column(name = "token", nullable = false, unique = true, length = 512)
    private String token;

    /** Plataforma do device: "ios" | "android" | "web" (informativo). */
    @Column(name = "platform", length = 20)
    private String platform;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    public DeviceToken(UUID userId, String token, String platform) {
        this.userId = userId;
        this.token = token;
        this.platform = platform;
    }

    @PrePersist
    void onCreate() {
        OffsetDateTime now = OffsetDateTime.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}
