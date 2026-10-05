package com.ospaulos.vupth.domain.professional.event;

import com.ospaulos.vupth.domain.professional.valueObject.ProfessionalStatus;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * O status de trabalho do profissional mudou (S1). Publicado pelo {@code save} do repositorio,
 * dentro da transacao de quem mudou — todos os caminhos (PATCH de status, fechar fila, fechar a
 * loja, auto-fechamento, fim da drenagem, abrir a loja, apagar a conta) passam por aqui.
 */
public record WorkStatusChangedEvent(
        UUID professionalId,
        UUID establishmentId,
        ProfessionalStatus from,
        ProfessionalStatus to,
        OffsetDateTime at
) {

    public boolean startsShift() {
        return !counts(from) && counts(to);
    }

    public boolean endsShift() {
        return counts(from) && !counts(to);
    }

    private static boolean counts(ProfessionalStatus status) {
        return status != null && status.countsAsOpen();
    }
}
