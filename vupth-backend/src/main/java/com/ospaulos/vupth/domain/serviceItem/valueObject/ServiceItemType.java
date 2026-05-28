package com.ospaulos.vupth.domain.serviceItem.valueObject;

import com.ospaulos.vupth.domain.establishment.valueObject.ServiceType;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Set;

@Getter
@AllArgsConstructor
public enum ServiceItemType {

    // BARBER
    CORTE_MAQUINA("Corte à máquina", Set.of(ServiceType.BARBER)),
    CORTE_TESOURA("Corte à tesoura", Set.of(ServiceType.BARBER)),
    BARBA("Barba", Set.of(ServiceType.BARBER)),
    SOBRANCELHA("Design de sobrancelha", Set.of(ServiceType.BARBER)),
    PINTURA("Pintura", Set.of(ServiceType.BARBER, ServiceType.BEAUTY_SALON)),

    // BEAUTY_SALON
    RECORTE_PONTAS("Recorte das pontas", Set.of(ServiceType.BEAUTY_SALON)),
    ESCOVA("Escova", Set.of(ServiceType.BEAUTY_SALON)),
    HIDRATACAO("Hidratação capilar", Set.of(ServiceType.BEAUTY_SALON)),
    PROGRESSIVA("Progressiva", Set.of(ServiceType.BEAUTY_SALON)),

    // NAIL_SALON
    MANICURE("Manicure", Set.of(ServiceType.NAIL_SALON)),
    PEDICURE("Pedicure", Set.of(ServiceType.NAIL_SALON)),
    UNHAS_GEL("Unhas em gel", Set.of(ServiceType.NAIL_SALON)),
    NAIL_ART("Nail art", Set.of(ServiceType.NAIL_SALON)),

    // SPA
    MASSAGEM_RELAXANTE("Massagem relaxante", Set.of(ServiceType.SPA)),
    MASSAGEM_MODELADORA("Massagem modeladora", Set.of(ServiceType.SPA)),
    LIMPEZA_PELE("Limpeza de pele", Set.of(ServiceType.SPA, ServiceType.CLINIC)),
    DEPILACAO("Depilação", Set.of(ServiceType.SPA)),
    DRENAGEM("Drenagem linfática", Set.of(ServiceType.SPA)),

    // CLINIC
    PEELING("Peeling facial", Set.of(ServiceType.CLINIC)),
    BOTOX("Aplicação de botox", Set.of(ServiceType.CLINIC)),
    MICROAGULHAMENTO("Microagulhamento", Set.of(ServiceType.CLINIC));

    private final String displayName;
    private final Set<ServiceType> serviceTypes;
}
