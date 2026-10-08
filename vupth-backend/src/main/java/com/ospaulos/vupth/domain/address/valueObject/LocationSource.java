package com.ospaulos.vupth.domain.address.valueObject;

/**
 * De onde veio a coordenada do endereco (origem-da-coordenada, Partner S39). Com isso, "o ponto ja foi
 * marcado pelo GPS" vale em qualquer aparelho do dono, nao so no que marcou.
 *
 * <ul>
 *   <li>{@link #GPS} — a latitude/longitude veio no corpo do POST ou do PATCH (o aparelho);</li>
 *   <li>{@link #GEOCODER} — o servidor achou pelo texto do endereco (BrasilAPI/Nominatim).</li>
 * </ul>
 * Nulo = sem coordenada, ou coordenada gravada antes deste campo (origem desconhecida).
 */
public enum LocationSource {
    GPS,
    GEOCODER
}
