package com.ospaulos.vupth.domain.establishment.service;

import java.util.UUID;

public interface EstablishmentOpenStatusService {

    /**
     * S1 (fatia 2) — recalcula o {@code open_status} do estabelecimento a partir dos
     * {@code work_status} dos profissionais afiliados: {@code aberto ⇔ ∃ prof em {WORKING, ON_BREAK}}.
     *
     * <p>Modelo HIBRIDO (transicao): o recalculo roda a cada mudanca de status e a cada
     * afiliacao/saida de profissional que <b>conta como presenca</b> (chegada/saida OFF nao muda a
     * presenca e nao recalcula), e VENCE nesses pontos; entre recalculos o toggle manual
     * ({@code PATCH /establishment/{id}/open-status}) continua funcionando como override.
     * Regras do recalculo:</p>
     * <ul>
     *   <li>derivado {@code OPEN}: sobrescreve qualquer status (inclusive TEMPORARILY_CLOSED manual);</li>
     *   <li>derivado {@code CLOSED}: so sobrescreve {@code OPEN} — um TEMPORARILY_CLOSED manual
     *       permanece (ja esta "fechado"; nao ha por que rebaixar o rotulo).</li>
     * </ul>
     *
     * <p>Roda NA MESMA transacao do chamador (recalculo atomico). Nao mexe na fila — fila existente
     * segue sendo atendida mesmo com a loja derivada CLOSED.</p>
     *
     * @return {@code true} se o {@code open_status} persistido mudou.
     */
    public boolean deriveFromProfessionals(UUID establishmentId);
}
