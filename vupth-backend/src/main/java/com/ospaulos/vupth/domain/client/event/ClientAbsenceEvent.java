package com.ospaulos.vupth.domain.client.event;

import com.ospaulos.vupth.domain.client.model.ClientAbsenceType;

import java.util.UUID;

/** O cliente nao chegou ou saiu da fila: quem ouve (ClientAbsenceService) grava a ocorrencia na mesma transacao. */
public record ClientAbsenceEvent(UUID clientId, UUID attendanceId, ClientAbsenceType type) {
}
