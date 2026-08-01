package com.ospaulos.vupth.infrastructure.geocoding;

import java.util.Optional;

/**
 * Resolve um endereco em coordenadas (backlog #2 — busca por proximidade / GPS).
 *
 * <p><b>Contrato:</b> nunca lanca. Nao conseguiu resolver (endereco incompleto, CEP inexistente,
 * servico fora do ar, timeout) devolve {@link Optional#empty()} — o cadastro do estabelecimento NAO
 * pode quebrar por causa do geocoding, ele so fica sem coordenada e invisivel na busca por raio ate
 * ser resolvido depois.</p>
 *
 * <p><b>Custo zero (decisao Andre 2026-07-27):</b> as implementacoes usam apenas servicos gratuitos
 * e sem chave. Ver {@link HttpGeocoder}.</p>
 */
public interface Geocoder {

    Optional<Coordinates> geocode(GeocodeQuery query);

    /**
     * Caminho inverso (fatia A2): dada a coordenada do aparelho do dono, devolve o endereco por
     * extenso para ele conferir. Mesmo contrato de {@code geocode}: <b>nunca lanca</b>, nao resolveu
     * devolve {@link Optional#empty()} — sem reverso o dono simplesmente digita o endereco na mao,
     * como sempre fez.
     */
    Optional<ReverseGeocodeResult> reverse(Coordinates coordinates);
}
