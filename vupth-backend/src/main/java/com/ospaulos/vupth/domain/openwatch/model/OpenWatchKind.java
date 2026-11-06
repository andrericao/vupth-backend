package com.ospaulos.vupth.domain.openwatch.model;

/** O que o cliente pediu para ser avisado (DEC-160, sempre opt-in). */
public enum OpenWatchKind {
    /** O profissional (ou, sem profissional, o primeiro da loja) comecou a atender hoje. */
    NOTIFY_OPEN,
    /** A fila comecou a andar: o primeiro atendimento do dia foi iniciado. Para quem ja tem horario marcado. */
    QUEUE_MOVING
}
