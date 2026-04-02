package com.ospaulos.vupth.domain.establishment.valueObject;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum OpenStatus {

    OPEN("aberto"),
    CLOSED("fechado"),
    TEMPORARILY_CLOSED("temporariamente fechado");

    private String openStatus;
}
