package com.ospaulos.vupth.domain.establishment.exception;

/**
 * Horario de funcionamento fora do modelo (#2): semana sem os 7 dias, dia repetido, dia com horario
 * e fechado ao mesmo tempo (ou nenhum dos dois), ou excecao em data passada.
 * Mapeada para HTTP 422 com code BUSINESS_HOURS_INVALID.
 */
public class InvalidBusinessHoursException extends RuntimeException {
    public InvalidBusinessHoursException(String message) {
        super(message);
    }
}
