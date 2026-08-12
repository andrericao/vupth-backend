package com.ospaulos.vupth.domain.address.valueObject;

import com.ospaulos.vupth.domain.address.exception.InvalidCoordinatesException;
import com.ospaulos.vupth.infrastructure.geocoding.Coordinates;

/**
 * Validacao do par latitude/longitude que chega pela API (backlog #2, fatia A2).
 *
 * <p><b>Por que existe:</b> desde a fatia A o {@code AddressRequest} aceita {@code latitude}/
 * {@code longitude} — e a fatia A2 transforma isso no caminho PRINCIPAL, porque o dono esta na loja
 * na hora do cadastro e o GPS do aparelho dele e mais preciso que o CEP. So que ate aqui o backend
 * gravava o que viesse, sem olhar. Ponto invalido gravado e pior que ponto ausente: sem coordenada a
 * loja apenas nao entra na busca por raio; com coordenada errada ela entra <b>no lugar errado</b>.</p>
 *
 * <p>Nao ha classe de instancia aqui de proposito — quem persiste e o {@code Address}, com os dois
 * {@code Double} nullable que a coluna ja tem. Isto e so o portao de entrada.</p>
 */
public final class AddressCoordinates {

    private AddressCoordinates() {
    }

    /**
     * Aceita o par que veio do request ou rejeita com {@link InvalidCoordinatesException} (422).
     *
     * <p><b>Ambos nulos e valido</b> — significa "sem coordenada", o caso normal de quem cadastra
     * digitando o endereco: o geocoding pos-commit resolve depois. O que nao passa e:</p>
     * <ul>
     *   <li><b>so um dos dois</b> — meia coordenada nao localiza nada, e aceitar em silencio deixaria
     *       o endereco num limbo em que o geocoding tambem nao o refaz;</li>
     *   <li><b>fora do globo</b> ou a <b>"ilha nula" {@code (0,0)}</b>, pelo criterio ja usado no
     *       resultado do geocoder ({@link Coordinates#isPlausible()}) — mesma regra nas duas pontas.</li>
     * </ul>
     */
    public static void validate(Double latitude, Double longitude) {
        if (latitude == null && longitude == null) {
            return;
        }
        if (latitude == null || longitude == null) {
            throw new InvalidCoordinatesException(
                    "latitude and longitude must be sent together (received only "
                            + (latitude == null ? "longitude" : "latitude") + ")");
        }
        if (!new Coordinates(latitude, longitude).isPlausible()) {
            throw new InvalidCoordinatesException(
                    "Invalid coordinates: " + latitude + "," + longitude
                            + ". Latitude must be between -90 and 90, longitude between -180 and 180,"
                            + " and (0,0) is not accepted.");
        }
    }
}
