package com.ospaulos.vupth.domain.establishment.exception;

import java.util.UUID;

/**
 * Abrir a loja na mao exige alguem para atender (S28 do Partner; Andre, 26/09): sem nenhum profissional
 * "trabalhando", a loja apareceria aberta para o cliente sem ninguem no local. HTTP 409
 * ESTABLISHMENT_NOBODY_WORKING.
 */
public class EstablishmentNobodyWorkingException extends RuntimeException {
    public EstablishmentNobodyWorkingException(UUID establishmentId) {
        super("Nao da para abrir a loja " + establishmentId + " sem nenhum profissional trabalhando: "
                + "marque alguem como trabalhando antes.");
    }
}
