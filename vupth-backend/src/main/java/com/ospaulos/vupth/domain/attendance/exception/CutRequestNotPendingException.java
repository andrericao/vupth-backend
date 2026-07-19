package com.ospaulos.vupth.domain.attendance.exception;

import java.util.UUID;

/**
 * Aceitar/recusar (BUG5 C2) so vale enquanto o pedido esta {@code PENDING}. Se ja foi aceito,
 * recusado ou expirou, a operacao e um no-op invalido. Mapeada para HTTP 409 com code
 * {@code CUT_REQUEST_NOT_PENDING}.
 */
public class CutRequestNotPendingException extends RuntimeException {
    public CutRequestNotPendingException(UUID cutRequestId, String currentStatus) {
        super("Cut request " + cutRequestId + " is not PENDING (current=" + currentStatus + ").");
    }
}
