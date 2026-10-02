package com.ospaulos.vupth.domain.attendance.exception;

/**
 * O botao "nao compareceu" saiu (Andre, 26/09): a regra dos 3 atrasos cobre o sumico — o profissional
 * so marca "atrasado", e no 3o o sistema cobra a multa e manda o cliente para o fim da fila. O endpoint
 * responde HTTP 410 NO_SHOW_REMOVED ate os apps tirarem o botao; depois sai de vez.
 */
public class NoShowRemovedException extends RuntimeException {
    public NoShowRemovedException() {
        super("O 'nao compareceu' foi substituido pela regra dos 3 atrasos: use PATCH /attendances/{id}/late.");
    }
}
