package com.ospaulos.vupth.domain.access.model;

/** O que gerou o registro de acesso (registro-de-acesso). */
public enum AccessEvent {
    /** {@code POST /auth/login} com sucesso. */
    LOGIN,
    /** {@code POST /auth/refresh} com sucesso (a sessao seguiu). */
    REFRESH
}
