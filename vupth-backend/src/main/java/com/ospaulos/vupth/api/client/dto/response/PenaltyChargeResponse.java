package com.ospaulos.vupth.api.client.dto.response;

import com.ospaulos.vupth.domain.client.model.PenaltyType;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Uma multa como o cliente a le na cobranca (D4): o valor e DE ONDE veio — "corte do dia tal com o profissional
 * tal" (Andre, 25/09). O cliente paga {@code amount} cheio: o valor do aviso nunca muda. Sem a taxa do gateway nem a
 * parte do app: o cliente nunca ve a margem do app (DEC-94); isso vive so no relatorio do painel.
 *
 * @param amount       o que o cliente paga (3.00 / 5.00)
 * @param originDate   horario do atendimento que gerou a multa
 */
public record PenaltyChargeResponse(
        UUID penaltyId,
        PenaltyType type,
        BigDecimal amount,
        UUID originAttendanceId,
        OffsetDateTime originDate,
        String originProfessionalName,
        String originEstablishmentName
) {}
