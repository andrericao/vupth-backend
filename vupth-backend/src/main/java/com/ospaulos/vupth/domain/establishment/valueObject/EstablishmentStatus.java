package com.ospaulos.vupth.domain.establishment.valueObject;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum EstablishmentStatus {

    ACTIVE("ativo"),
    INACTIVE("inativo"),
    TEMPORARY_CLOSED("Temporariamente inativo!");

    private String status;
}
