package com.ospaulos.vupth.domain.establishment.valueObject;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum EstablishmentStatus {

    ACTIVE("ativo"),
    INACTIVE("inativo"),
    TEMPORARY_CLOSED("Temporariamente inativo!"),
    /** O dono desativou a conta sem passar a posse (desativar-conta): fora da vitrine, volta quando ele voltar. */
    DEACTIVATED("desativada"),
    /** O dono apagou a loja: a linha fica pelo historico dos atendimentos, com o nome "Loja removida". */
    REMOVED("removida");

    private String status;
}
