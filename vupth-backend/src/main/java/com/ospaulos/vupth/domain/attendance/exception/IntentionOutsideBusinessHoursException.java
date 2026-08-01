package com.ospaulos.vupth.domain.attendance.exception;

import java.time.OffsetDateTime;

/**
 * Intencao de atendimento (backlog #4). Lancada quando o {@code desiredTime} pedido cai FORA do
 * horario de funcionamento do estabelecimento ({@code [openingTime, closingTime)}). Uma intencao
 * so faz sentido para um horario em que a loja realmente atende.
 *
 * Mapeada para HTTP 422 com code INTENTION_OUTSIDE_BUSINESS_HOURS.
 */
public class IntentionOutsideBusinessHoursException extends RuntimeException {
    public IntentionOutsideBusinessHoursException(OffsetDateTime desiredTime) {
        super("Horario pretendido " + desiredTime + " esta fora do horario de funcionamento da loja. "
                + "Escolha um horario dentro do expediente.");
    }
}
