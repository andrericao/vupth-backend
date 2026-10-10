package com.ospaulos.vupth.api.payment.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Corpo opcional do PATCH /attendances/{id}/finish (pagamento-pix, adendo 1): a resposta do modal
 * "o pagamento foi efetuado?". {@code true} termina e marca PAID; {@code false} ou sem corpo, so termina.
 */
public record FinishAttendanceRequest(
        @JsonProperty("payment_received") Boolean paymentReceived
) {
    public boolean received() {
        return Boolean.TRUE.equals(paymentReceived);
    }
}
