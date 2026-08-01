package com.ospaulos.vupth.infrastructure.geocoding;

/**
 * Endereco a resolver, achatado para nao arrastar a entidade JPA ate a camada de infraestrutura
 * (o geocoder roda em thread propria, fora da sessao do Hibernate).
 *
 * @param zipCode CEP em <b>8 digitos puros</b>, sem hifen — convencao de {@code Address.zipCode}
 */
public record GeocodeQuery(String zipCode,
                           String street,
                           String number,
                           String neighborhood,
                           String city,
                           String state) {

    /** CEP utilizavel na consulta por CEP (a via primaria, gratuita e sem chave). */
    public boolean hasUsableZipCode() {
        return zipCode != null && zipCode.matches("\\d{8}");
    }

    /** Endereco textual minimo para a busca livre (fallback): sem rua e cidade nao ha o que procurar. */
    public boolean hasUsableStreetAddress() {
        return street != null && !street.isBlank() && city != null && !city.isBlank();
    }
}
