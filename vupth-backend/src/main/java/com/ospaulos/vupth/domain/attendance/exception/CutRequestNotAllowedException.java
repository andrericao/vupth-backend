package com.ospaulos.vupth.domain.attendance.exception;

import java.util.UUID;

/**
 * O pedido de corte (BUG5 C2) so faz sentido quando a loja esta FECHADA p/ novos (gate C1). Se a
 * loja esta "aberta agora", o cliente deve entrar normalmente via {@code POST /attendances} — nao ha
 * o que aprovar. Mapeada para HTTP 409 com code {@code CUT_REQUEST_NOT_ALLOWED}.
 */
public class CutRequestNotAllowedException extends RuntimeException {
    public CutRequestNotAllowedException(UUID establishmentId) {
        super("Cut request not allowed: establishment " + establishmentId
                + " is open now — enter the queue normally.");
    }
}
