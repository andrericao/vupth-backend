package com.ospaulos.vupth.domain.attendance.cut;

/**
 * Estado do pedido de corte (BUG5 C2 — "solicitar corte"). O cliente pede quando a loja esta
 * fechada p/ novos (gate C1); o profissional aceita ou recusa, e um timer server-side auto-nega
 * se ninguem responder ate {@code expiresAt} (5min).
 *
 * <p>Ciclo: {@code PENDING} -> {@code ACCEPTED} (profissional aceitou; nasce um Attendance SCHEDULED)
 * / {@code REFUSED} (profissional recusou) / {@code EXPIRED} (venceu sem resposta). So {@code PENDING}
 * e mutavel.</p>
 */
public enum CutRequestStatus {
    PENDING,
    ACCEPTED,
    REFUSED,
    EXPIRED
}
