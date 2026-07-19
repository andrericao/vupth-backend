package com.ospaulos.vupth.domain.attendance.exception;

import java.util.UUID;

/**
 * O pedido de corte (BUG5 C2) ja passou da validade ({@code expires_at}, 5min) quando o profissional
 * tentou aceitar. O cliente so entra com aceite explicito DENTRO da janela — passou, auto-nega. Vale
 * mesmo se o timer de auto-nega (fatia 3) ainda nao rodou (checagem defensiva no aceite). Mapeada
 * para HTTP 409 com code {@code CUT_REQUEST_EXPIRED}.
 */
public class CutRequestExpiredException extends RuntimeException {
    public CutRequestExpiredException(UUID cutRequestId) {
        super("Cut request " + cutRequestId + " has expired.");
    }
}
