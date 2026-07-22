package com.ospaulos.vupth.domain.professional.exception;

import java.util.UUID;

/**
 * Lancada quando um profissional tenta entrar em expediente (status WORKING/ON_BREAK) sem ter
 * ao menos um servico cadastrado (S1, mesmo gate do #31 de abertura da loja). Sem servico ele nao
 * pode atender, entao nao pode "contar como aberto".
 *
 * <p>Mapeada para HTTP 422 com code {@code PROFESSIONAL_HAS_NO_SERVICE}.</p>
 */
public class ProfessionalHasNoServiceException extends RuntimeException {
    public ProfessionalHasNoServiceException(UUID professionalId) {
        super("Professional " + professionalId + " has no service and cannot start working.");
    }
}
