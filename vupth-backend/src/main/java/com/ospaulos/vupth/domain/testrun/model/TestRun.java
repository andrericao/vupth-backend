package com.ospaulos.vupth.domain.testrun.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Uma rodada de teste (modo-piloto-sem-cobranca, DEC-106; varias lojas, DEC-108): o ADMIN a declara pelo painel, com um nome,
 * escolhe as lojas (tabela test_run_store) e a encerra depois. Uma loja so esta em um teste ativo. Os atendimentos criados enquanto ela esta ativa levam a flag test e o id dela.
 */
@Getter
@Setter
@Entity
@NoArgsConstructor
@Table(name = "test_run")
public class TestRun {

    @Id
    private UUID id;

    @Column(name = "name", length = 120, nullable = false)
    private String name;

    @Column(name = "started_at", nullable = false)
    private OffsetDateTime startedAt;

    @Column(name = "ended_at")
    private OffsetDateTime endedAt;

    @Column(name = "created_by")
    private UUID createdBy;

    public boolean isActive() {
        return endedAt == null;
    }
}
