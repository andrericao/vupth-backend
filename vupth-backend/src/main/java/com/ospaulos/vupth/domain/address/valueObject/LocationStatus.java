package com.ospaulos.vupth.domain.address.valueObject;

import com.ospaulos.vupth.domain.address.model.Address;

/**
 * Se o endereco ja virou ponto no mapa (BAK · geocodificacao-silenciosa, Partner S25). Sem coordenada a
 * loja nao entra na busca por proximidade, e ate aqui o dono nao tinha como saber.
 *
 * <ul>
 *   <li>{@link #LOCATED} — tem latitude e longitude: entra na busca por raio;</li>
 *   <li>{@link #PENDING} — sem coordenada, geocoding na fila (segundos depois do cadastro ou da correcao);</li>
 *   <li>{@link #UNRESOLVED} — o geocoder nao achou o endereco (ou estava fora do ar). O dono corrige o
 *       endereco ({@code PATCH}, que tenta de novo) ou manda a posicao do aparelho.</li>
 * </ul>
 */
public enum LocationStatus {
    LOCATED,
    PENDING,
    UNRESOLVED;

    public static LocationStatus of(Address address) {
        if (address.getLatitude() != null && address.getLongitude() != null) {
            return LOCATED;
        }
        return address.isGeocodingFailed() ? UNRESOLVED : PENDING;
    }
}
