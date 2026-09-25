package com.ospaulos.vupth.domain.professional.exception;

import java.util.UUID;

/**
 * O profissional trabalha so com agenda (D9): nao ha fila para entrar — o caminho e agendar
 * ({@code POST /attendances/intentions}). HTTP 409 PROFESSIONAL_AGENDA_ONLY.
 */
public class ProfessionalAgendaOnlyException extends RuntimeException {
    public ProfessionalAgendaOnlyException(UUID professionalId) {
        super("Este profissional trabalha so com agendamento (" + professionalId + "): escolha um horario.");
    }
}
