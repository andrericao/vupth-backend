package com.ospaulos.vupth.api.payment.dto.request;

import jakarta.validation.constraints.Size;

/** POST /attendances/{id}/payment/contest (F4): o motivo e opcional, ate 300 caracteres. */
public record ContestPaymentRequest(@Size(max = 300) String reason) {
}
