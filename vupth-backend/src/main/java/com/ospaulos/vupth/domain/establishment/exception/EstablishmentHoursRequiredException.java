package com.ospaulos.vupth.domain.establishment.exception;

import java.util.UUID;

/**
 * Lançada quando um establishment tenta abrir (open-status = OPEN) sem ter
 * definido o horário de funcionamento (abertura/fechamento).
 *
 * O front usa este 422 para encaminhar o profissional à tela de horário antes
 * de poder declarar o estabelecimento aberto (#11g2c).
 * Mapeada para HTTP 422 com code ESTABLISHMENT_HOURS_REQUIRED.
 */
public class EstablishmentHoursRequiredException extends RuntimeException {
    public EstablishmentHoursRequiredException(UUID id) {
        super("Establishment " + id + " não pode abrir: defina o horário de funcionamento (abertura e fechamento).");
    }
}
