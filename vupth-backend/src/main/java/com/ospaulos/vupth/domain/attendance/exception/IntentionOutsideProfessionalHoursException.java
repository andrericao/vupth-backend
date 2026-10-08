package com.ospaulos.vupth.domain.attendance.exception;

import java.time.OffsetDateTime;

/**
 * Intencao num horario em que o profissional nao atende pela agenda dele (agenda-do-profissional),
 * embora a loja esteja aberta. Mapeada para HTTP 422 com code INTENTION_OUTSIDE_PROFESSIONAL_HOURS.
 */
public class IntentionOutsideProfessionalHoursException extends RuntimeException {
    public IntentionOutsideProfessionalHoursException(OffsetDateTime desiredTime) {
        super("Horario pretendido " + desiredTime + " esta fora da agenda do profissional. "
                + "Escolha um horario em que ele atende.");
    }
}
