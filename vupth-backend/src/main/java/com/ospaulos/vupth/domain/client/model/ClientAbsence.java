package com.ospaulos.vupth.domain.client.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Uma ocorrencia do cliente (nao cheguei ou saida da fila), gravada tambem para o cliente isento da multa. */
@Getter
@Entity
@NoArgsConstructor
@Table(name = "client_absence",
        uniqueConstraints = @UniqueConstraint(name = "uk_client_absence_attendance_type", columnNames = {"attendance_id", "type"}))
public class ClientAbsence {

    @Id
    private UUID id;

    @Column(name = "client_id", nullable = false)
    private UUID clientId;

    @Column(name = "attendance_id", nullable = false)
    private UUID attendanceId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", length = 12, nullable = false)
    private ClientAbsenceType type;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    public ClientAbsence(UUID clientId, UUID attendanceId, ClientAbsenceType type, OffsetDateTime createdAt) {
        this.id = UUID.randomUUID();
        this.clientId = clientId;
        this.attendanceId = attendanceId;
        this.type = type;
        this.createdAt = createdAt;
    }
}
