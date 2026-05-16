package com.ospaulos.vupth.domain.serviceItem.valueObject;

import com.ospaulos.vupth.domain.establishment.valueObject.ServiceType;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Set;

@Getter
@AllArgsConstructor
public enum ServiceItemType {

    PEDICURE("Pedicure", Set.of(ServiceType.NAIL_SALON)),
    CORTE_MAQUINA("Corte à máquina", Set.of(ServiceType.BARBER)),
    RECORTE_PONTAS("Recorte das pontas", Set.of(ServiceType.BEAUTY_SALON)),
    PINTURA("Pintura", Set.of(ServiceType.BARBER, ServiceType.BEAUTY_SALON)),
    MANICURE("Manicure", Set.of(ServiceType.NAIL_SALON));

    private final String displayName;
    private final Set<ServiceType> serviceTypes;
}
