package com.ospaulos.vupth.domain.serviceItem.valueObject;

import com.ospaulos.vupth.domain.establishment.valueObject.ServiceType;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Set;

@Getter
@AllArgsConstructor
public enum ServiceItemType {

    PEDICURE("Pedicure", Set.of(ServiceType.MANICURE)),
    CORTE_MAQUINA("Corte à máquina", Set.of(ServiceType.BARBER)),
    RECORTE_PONTAS("Recorte das pontas", Set.of(ServiceType.SALON_BEAUTY)),
    PINTURA("Pintura", Set.of(ServiceType.BARBER, ServiceType.SALON_BEAUTY)),
    MANICURE("Manicure", Set.of(ServiceType.MANICURE));

    private final String displayName;
    private final Set<ServiceType> serviceTypes;
}
