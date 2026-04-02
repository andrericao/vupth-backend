package com.ospaulos.vupth.domain.address.valueObject;


import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum AddressType {
    HOME("casa"),
    WORK("trabalho"),
    OTHER("outro");

    private String address;
}
