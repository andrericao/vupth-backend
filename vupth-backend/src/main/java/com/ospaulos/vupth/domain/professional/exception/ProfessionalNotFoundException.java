package com.ospaulos.vupth.domain.professional.exception;

import java.util.UUID;

public class ProfessionalNotFoundException extends RuntimeException {
    public ProfessionalNotFoundException(UUID id) {
        super("Perfil de profissional não encontrado (" + id + "). Entre na loja como profissional, ou peça ao dono para te afiliar, e tente de novo.");
    }
}