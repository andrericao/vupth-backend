package com.ospaulos.vupth.domain.attendance.fitin.model;

/** O estado do pedido de encaixe (DEC-136/139): PENDING espera o profissional, COUNTERED espera o cliente. */
public enum FitInStatus {
    PENDING, COUNTERED, ACCEPTED, DECLINED, EXPIRED, CANCELLED;

    /** Ainda negociando: aberto para aceitar, contrapor, recusar ou cancelar. */
    public boolean open() {
        return this == PENDING || this == COUNTERED;
    }
}
