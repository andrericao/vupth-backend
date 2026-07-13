package com.ospaulos.vupth.domain.attendance.exception;

import java.time.OffsetDateTime;

/**
 * Intencao de atendimento (backlog #4) — estado CHEIO/FULL. Lancada quando NAO cabe mais nenhum
 * atendimento no expediente do dia pretendido: o fim projetado do encaixe da intencao
 * ({@code estimatedStart + duracao}) ultrapassaria o {@code closingTime}. E o rotulo CHEIO por
 * profissional/dia (derivado e temporario — reabre quando a fila drena).
 *
 * Mapeada para HTTP 409 com code PROFESSIONAL_AGENDA_FULL.
 */
public class ProfessionalAgendaFullException extends RuntimeException {
    public ProfessionalAgendaFullException(OffsetDateTime desiredTime) {
        super("Agenda cheia para " + desiredTime + ": nao ha horario disponivel antes do fechamento neste dia.");
    }
}
