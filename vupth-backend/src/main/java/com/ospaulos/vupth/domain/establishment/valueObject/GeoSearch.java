package com.ospaulos.vupth.domain.establishment.valueObject;

import com.ospaulos.vupth.domain.address.exception.InvalidCoordinatesException;
import com.ospaulos.vupth.domain.address.valueObject.AddressCoordinates;

/**
 * Criterio de busca por proximidade da listagem publica (backlog #2, fatia B).
 *
 * <p><b>Decisao de produto (Andre 2026-07-27, opcao A): NAO existe raio default.</b> Com
 * {@code lat}/{@code lng} e sem {@code radiusKm}, a busca devolve <b>tudo</b> ordenado por
 * distancia; o raio e filtro <b>opcional e explicito</b>. Um raio default deixaria a tela vazia para
 * quem esta numa regiao sem loja — e o usuario nao distingue "nao ha nada perto" de "o app
 * quebrou".</p>
 */
public record GeoSearch(double latitude, double longitude, Double radiusKm) {

    /**
     * Monta o criterio a partir dos parametros crus da query string, ou devolve {@code null} quando
     * nao ha busca por proximidade nenhuma (o caminho normal de quem nao concedeu o GPS).
     *
     * <p><b>Regras:</b></p>
     * <ul>
     *   <li>sem {@code lat} e sem {@code lng} → {@code null}, e a listagem cai na ordenacao de
     *       sempre (por tempo de espera). Vale mesmo que venha {@code radiusKm}: raio sem origem
     *       nao quer dizer nada, e <b>e ignorado de proposito, sem erro</b> — a lista nunca pode
     *       vir vazia (nem quebrar) so porque o usuario negou a permissao de localizacao e o app
     *       mandou um raio guardado de antes;</li>
     *   <li>coordenada pela metade, fora do globo ou {@code (0,0)} → {@code 422}, pelo mesmo portao
     *       de entrada do cadastro ({@link AddressCoordinates#validate});</li>
     *   <li>{@code radiusKm <= 0} → {@code 422}. Diferente do caso acima, aqui o chamador disse o
     *       que queria e disse algo impossivel: raio nao-positivo nao filtra nada, devolveria lista
     *       vazia sempre e o front leria isso como "nao ha lojas".</li>
     * </ul>
     */
    public static GeoSearch from(Double latitude, Double longitude, Double radiusKm) {
        if (latitude == null && longitude == null) {
            return null;
        }
        AddressCoordinates.validate(latitude, longitude);

        if (radiusKm != null && radiusKm <= 0) {
            throw new InvalidCoordinatesException(
                    "radiusKm must be greater than zero (received " + radiusKm + ")");
        }
        return new GeoSearch(latitude, longitude, radiusKm);
    }

    /** true quando o raio foi pedido — o que faz a loja SEM coordenada sumir da lista. */
    public boolean hasRadius() {
        return radiusKm != null;
    }
}
