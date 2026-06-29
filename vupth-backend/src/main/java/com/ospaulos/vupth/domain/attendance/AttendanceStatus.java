package com.ospaulos.vupth.domain.attendance;

public enum AttendanceStatus {
    SCHEDULED,
    IN_PROGRESS,
    FINISHED,
    CANCELLED,
    PAUSED,
    /**
     * Cliente que nao chegou a tempo (no-show parcial): o profissional o "pula", ele
     * cai uma posicao na fila (item #19) — mas NAO sai. Continua na fila como LATE,
     * pode voltar a SCHEDULED (undo) ou ser atendido direto (start) quando chegar.
     */
    LATE
}
