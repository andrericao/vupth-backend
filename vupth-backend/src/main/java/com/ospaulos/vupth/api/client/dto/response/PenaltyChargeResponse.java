package com.ospaulos.vupth.api.client.dto.response;

import com.ospaulos.vupth.domain.client.model.PenaltyType;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Uma multa como o cliente a le na cobranca (D4): o valor, para onde ele vai e DE ONDE veio — "corte do
 * dia tal com o profissional tal" (Andre, 25/09). A multa vai inteira para o app; o gateway leva a
 * porcentagem dele sobre a transacao. O cliente paga {@code amount} cheio: o valor do aviso nunca muda.
 *
 * @param amount       o que o cliente paga (3.00 / 5.00)
 * @param gatewayFee   a parte da empresa de pagamento (4%)
 * @param appShare     o que fica com o app ({@code amount - gatewayFee})
 * @param originDate   horario do atendimento que gerou a multa
 */
public record PenaltyChargeResponse(
        UUID penaltyId,
        PenaltyType type,
        BigDecimal amount,
        BigDecimal gatewayFee,
        BigDecimal appShare,
        UUID originAttendanceId,
        OffsetDateTime originDate,
        String originProfessionalName,
        String originEstablishmentName
) {}
