package com.ospaulos.vupth.domain.establishment.valueObject;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum ServiceType {

    BARBER("barbeiro"),
    BEAUTY_SALON("salaoBeleza"),
    EYEBROW("sobrancelha"),
    NAIL_SALON("manicure"),
    SPA("spa"),
    CLINIC("clinica");

    private String service;
}
