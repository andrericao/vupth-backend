package com.ospaulos.vupth.domain.user.model.valueObject;

public enum RoleType {

    OWNER,
    EMPLOYEE,
    PROFESSIONAL,
    PARTNER,
    SECRETARY,
    CLIENT,
    /** O Andre no painel (campanha-pelo-painel, DEC-69). So nasce por migracao, nunca pelo app. */
    ADMIN;
}
