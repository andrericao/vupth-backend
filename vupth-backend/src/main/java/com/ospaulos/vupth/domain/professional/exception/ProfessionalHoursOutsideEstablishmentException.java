package com.ospaulos.vupth.domain.professional.exception;

/**
 * Agenda do profissional (agenda-do-profissional) com um dia que passa do horario da loja: o
 * profissional so atende dentro do expediente dela. Mapeada para HTTP 422 com code
 * PROFESSIONAL_HOURS_OUTSIDE_ESTABLISHMENT.
 */
public class ProfessionalHoursOutsideEstablishmentException extends RuntimeException {
    public ProfessionalHoursOutsideEstablishmentException(String day) {
        super("O horario de " + day + " passa do horario da loja. Escolha um horario dentro do expediente dela.");
    }
}
