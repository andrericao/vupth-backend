package com.ospaulos.vupth.domain.affiliation.model;

/** Convite por e-mail (convite-sem-conta): esperando a conta, ja virou convite comum, ou cancelado pelo dono. */
public enum EmailInvitationStatus {
    PENDING,
    CONVERTED,
    CANCELLED
}
