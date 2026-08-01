package com.ospaulos.vupth.infrastructure.geocoding;

/**
 * Par latitude/longitude resolvido por um {@link Geocoder} (backlog #2 — busca por proximidade).
 *
 * @param latitude  graus decimais, −90..90
 * @param longitude graus decimais, −180..180
 */
public record Coordinates(double latitude, double longitude) {

    /**
     * Descarta coordenada fora do globo e a "ilha nula" (0,0) — que no Golfo da Guine e o resultado
     * classico de um geocoder que na verdade nao achou nada e devolveu zeros.
     */
    public boolean isPlausible() {
        if (latitude < -90 || latitude > 90 || longitude < -180 || longitude > 180) {
            return false;
        }
        return !(latitude == 0 && longitude == 0);
    }
}
