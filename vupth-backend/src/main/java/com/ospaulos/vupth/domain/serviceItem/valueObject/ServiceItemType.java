package com.ospaulos.vupth.domain.serviceItem.valueObject;

import com.ospaulos.vupth.domain.establishment.valueObject.ServiceType;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Set;

/**
 * Catalogo fixo de servicos. Cada tipo tem:
 *  - displayName: nome oficial/canonico exibido ao cliente;
 *  - serviceTypes: categorias (ServiceType) as quais o servico pertence;
 *  - keywords: sinonimos/aliases regionais para a busca (item 34). O cliente pode
 *    procurar pelo nome que conhece (ex.: "luzes") e o backend resolve para o tipo
 *    canonico (PINTURA), retornando o alias que casou para o front exibir o "tambem
 *    conhecido como". A correspondencia e feita com normalizacao (sem acento, caixa
 *    baixa) tanto da query quanto do displayName/keywords — ver ServiceTypeSearch.
 *    Sinonimos sao do SERVICO (iguais para todas as lojas), nao por estabelecimento.
 */
@Getter
@AllArgsConstructor
public enum ServiceItemType {

    // BARBER
    CORTE_MAQUINA("Corte à máquina", Set.of(ServiceType.BARBER),
            Set.of("maquina", "corte na maquina", "corte baixo", "degrade")),
    CORTE_TESOURA("Corte à tesoura", Set.of(ServiceType.BARBER),
            Set.of("tesoura", "corte na tesoura", "corte social")),
    BARBA("Barba", Set.of(ServiceType.BARBER),
            Set.of("barba", "barboterapia", "aparar a barba", "fazer a barba")),
    SOBRANCELHA("Design de sobrancelha", Set.of(ServiceType.BARBER, ServiceType.EYEBROW),
            Set.of("sobrancelha", "design de sobrancelha", "sobrancelha na navalha")),
    PINTURA("Pintura", Set.of(ServiceType.BARBER, ServiceType.BEAUTY_SALON),
            Set.of("pintura", "tintura", "coloracao", "luzes", "mechas", "reflexo", "platinado")),

    // EYEBROW
    HENNA_SOBRANCELHA("Henna para sobrancelha", Set.of(ServiceType.EYEBROW),
            Set.of("henna", "henna para sobrancelha")),
    MICROPIGMENTACAO_SOBRANCELHA("Micropigmentação fio a fio", Set.of(ServiceType.EYEBROW),
            Set.of("micropigmentacao", "fio a fio", "microblading")),
    BROW_LAMINATION("Brow lamination", Set.of(ServiceType.EYEBROW),
            Set.of("brow lamination", "laminacao de sobrancelha")),
    TINTURA_SOBRANCELHA("Tintura para sobrancelha", Set.of(ServiceType.EYEBROW),
            Set.of("tintura de sobrancelha", "pintar sobrancelha")),

    // BEAUTY_SALON
    RECORTE_PONTAS("Recorte das pontas", Set.of(ServiceType.BEAUTY_SALON),
            Set.of("pontas", "aparar as pontas", "corte das pontas")),
    ESCOVA("Escova", Set.of(ServiceType.BEAUTY_SALON),
            Set.of("escova", "brushing", "escova modeladora")),
    HIDRATACAO("Hidratação capilar", Set.of(ServiceType.BEAUTY_SALON),
            Set.of("hidratacao", "hidratacao capilar", "umectacao")),
    PROGRESSIVA("Progressiva", Set.of(ServiceType.BEAUTY_SALON),
            Set.of("progressiva", "escova progressiva", "alisamento")),

    // NAIL_SALON
    MANICURE("Manicure", Set.of(ServiceType.NAIL_SALON),
            Set.of("manicure", "fazer as unhas", "unha da mao")),
    PEDICURE("Pedicure", Set.of(ServiceType.NAIL_SALON),
            Set.of("pedicure", "unha do pe", "pezinho")),
    UNHAS_GEL("Unhas em gel", Set.of(ServiceType.NAIL_SALON),
            Set.of("unhas em gel", "unha de gel", "alongamento de unha", "postica")),
    NAIL_ART("Nail art", Set.of(ServiceType.NAIL_SALON),
            Set.of("nail art", "decoracao de unha", "unha decorada")),

    // SPA
    MASSAGEM_RELAXANTE("Massagem relaxante", Set.of(ServiceType.SPA),
            Set.of("massagem", "massagem relaxante", "relaxante")),
    MASSAGEM_MODELADORA("Massagem modeladora", Set.of(ServiceType.SPA),
            Set.of("massagem modeladora", "modeladora")),
    LIMPEZA_PELE("Limpeza de pele", Set.of(ServiceType.SPA, ServiceType.CLINIC),
            Set.of("limpeza de pele", "limpeza facial")),
    DEPILACAO("Depilação", Set.of(ServiceType.SPA),
            Set.of("depilacao", "depilar", "cera")),
    DRENAGEM("Drenagem linfática", Set.of(ServiceType.SPA),
            Set.of("drenagem", "drenagem linfatica", "linfatica")),

    // CLINIC
    PEELING("Peeling facial", Set.of(ServiceType.CLINIC),
            Set.of("peeling", "peeling facial")),
    BOTOX("Aplicação de botox", Set.of(ServiceType.CLINIC),
            Set.of("botox", "toxina botulinica", "aplicacao de botox")),
    MICROAGULHAMENTO("Microagulhamento", Set.of(ServiceType.CLINIC),
            Set.of("microagulhamento", "agulhamento"));

    private final String displayName;
    private final Set<ServiceType> serviceTypes;
    private final Set<String> keywords;
}
