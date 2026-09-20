package com.ospaulos.vupth.domain.professional.exception;

import java.util.UUID;

/**
 * O profissional nao recebe cliente novo na fila viva agora. Mapeada para 409
 * {@code PROFESSIONAL_NOT_ACCEPTING_NEW}. O cliente ainda pode agendar para uma data futura
 * (intencao de atendimento #4, fora da fila).
 *
 * <p>Duas causas, <b>um codigo so</b>: a fila fechada na mao (S1 fatia 3, {@code accepting_new =
 * false}) e o profissional <b>fora de expediente</b> (regra de 07/09, fechada em 20/09). O codigo
 * nao se divide porque o que o cliente precisa saber e o mesmo — "este profissional nao esta
 * pegando gente agora" — e um codigo novo apareceria no app como mensagem crua ate os fronts
 * tratarem. A <b>mensagem</b> distingue, para o log e para quem investiga.</p>
 */
public class ProfessionalNotAcceptingNewException extends RuntimeException {

    private ProfessionalNotAcceptingNewException(String message) {
        super(message);
    }

    /** Fila fechada na mao — o profissional esta drenando o que ja tem. */
    public static ProfessionalNotAcceptingNewException queueClosed(UUID professionalId) {
        return new ProfessionalNotAcceptingNewException(
                "Professional " + professionalId + " is not accepting new clients (queue closed).");
    }

    /** Fora de expediente: nao marcou "trabalhando" — entende-se que nao esta na loja. */
    public static ProfessionalNotAcceptingNewException notInShift(UUID professionalId) {
        return new ProfessionalNotAcceptingNewException(
                "Professional " + professionalId + " is not accepting new clients (not in shift).");
    }
}
