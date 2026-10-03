package com.ospaulos.vupth.domain.professional.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Um turno de expediente do profissional (Partner S4): comeca quando ele sai de Fora para
 * Trabalhando/Intervalo e termina quando volta a Fora. {@code endedAt} nulo = turno em andamento.
 * O intervalo (ON_BREAK) fica dentro do turno — o profissional segue no local.
 */
@Getter
@Entity
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "professional_shift")
public class ProfessionalShift {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "professional_id", nullable = false)
    private UUID professionalId;

    /** Loja em que o turno aconteceu; nulo se ele ainda nao tinha loja. */
    @Column(name = "establishment_id")
    private UUID establishmentId;

    @Column(name = "started_at", nullable = false)
    private OffsetDateTime startedAt;

    @Column(name = "ended_at")
    private OffsetDateTime endedAt;

    public ProfessionalShift(UUID professionalId, UUID establishmentId, OffsetDateTime startedAt) {
        this.professionalId = professionalId;
        this.establishmentId = establishmentId;
        this.startedAt = startedAt;
    }

    public void end(OffsetDateTime at) {
        this.endedAt = at;
    }

    public boolean isOpen() {
        return endedAt == null;
    }
}
