package com.ospaulos.vupth.api.payment.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.ospaulos.vupth.domain.payment.PaymentMethod;

/** Corpo opcional do "Recebi" ({@code POST /attendances/{id}/payment/confirm}): como o pagamento foi recebido. */
public record ConfirmPaymentRequest(@JsonProperty("method") PaymentMethod method) {
}
