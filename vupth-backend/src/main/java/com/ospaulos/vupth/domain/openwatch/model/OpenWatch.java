package com.ospaulos.vupth.domain.openwatch.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Um pedido de aviso do cliente logado (avisar-quando-abrir, BAK-8, DEC-160). Vale so para {@link #day}: a meia-noite
 * deixa de valer sozinho (as consultas filtram pelo dia de hoje), sem recorrencia. Dispara uma vez ({@link #firedAt}).
 */
@Getter
@Entity
@Table(name = "open_watch")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OpenWatch {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "establishment_id", nullable = false)
    private UUID establishmentId;

    @Column(name = "professional_id")
    private UUID professionalId;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", length = 20, nullable = false)
    private OpenWatchKind kind;

    @Column(name = "day", nullable = false)
    private LocalDate day;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "fired_at")
    private OffsetDateTime firedAt;

    public OpenWatch(UUID userId, UUID establishmentId, UUID professionalId, OpenWatchKind kind, LocalDate day) {
        this.id = UUID.randomUUID();
        this.userId = userId;
        this.establishmentId = establishmentId;
        this.professionalId = professionalId;
        this.kind = kind;
        this.day = day;
        this.createdAt = OffsetDateTime.now();
    }

    /** Este pedido cobre o profissional? Sem profissional no pedido, vale qualquer um da loja. */
    public boolean covers(UUID professional) {
        return professionalId == null || professionalId.equals(professional);
    }

    public void markFired() {
        this.firedAt = OffsetDateTime.now();
    }
}
