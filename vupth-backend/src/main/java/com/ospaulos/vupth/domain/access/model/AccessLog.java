package com.ospaulos.vupth.domain.access.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Registro de acesso a aplicacao (Marco Civil da Internet, art. 15; registro-de-acesso, 10/10): quem,
 * de qual IP e porta, e quando — a cada login e a cada renovacao do token. Guardado por 6 meses, depois
 * apagado ({@code AccessLogPurgeScheduler}). Sem FK no usuario de proposito: a obrigacao de guardar vale
 * mesmo depois que a conta e apagada.
 */
@Getter
@Entity
@NoArgsConstructor
@Table(name = "access_log")
public class AccessLog {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "event", length = 20, nullable = false)
    private AccessEvent event;

    @Column(name = "ip", length = 45, nullable = false)
    private String ip;

    /** A porta de origem; {@code null} quando a plataforma nao a informa (atras do proxy). */
    @Column(name = "port")
    private Integer port;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    public AccessLog(UUID userId, AccessEvent event, String ip, Integer port, OffsetDateTime createdAt) {
        this.id = UUID.randomUUID();
        this.userId = userId;
        this.event = event;
        this.ip = ip;
        this.port = port;
        this.createdAt = createdAt;
    }
}
