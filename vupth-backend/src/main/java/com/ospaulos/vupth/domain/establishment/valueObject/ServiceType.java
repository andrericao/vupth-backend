package com.ospaulos.vupth.domain.establishment.valueObject;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum ServiceType {

    BARBER("barbeiro"),
    SALON_BEAUTY("salaoBeleza"),
    EYEBROW("sobrancelha"),
    MANICURE("manicure");

    private String service;
}
