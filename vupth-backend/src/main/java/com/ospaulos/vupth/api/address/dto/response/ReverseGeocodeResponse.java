package com.ospaulos.vupth.api.address.dto.response;

import com.ospaulos.vupth.infrastructure.geocoding.ReverseGeocodeResult;

/**
 * Endereco sugerido a partir da coordenada do aparelho (backlog #2, fatia A2).
 *
 * <p>Os campos espelham o {@code AddressRequest} <b>de proposito</b>: o Partner joga esta resposta
 * direto no formulario de cadastro, o dono confere e corrige o que faltou (o reverso quase nunca
 * sabe o numero da casa), e o formulario volta como um {@code AddressRequest} normal — com a
 * {@code latitude}/{@code longitude} do GPS junto.</p>
 *
 * <p><b>Campos podem vir nulos</b> e o front precisa aguentar isso: o reverso da o que sabe. O
 * {@code displayName} e o endereco numa linha so, e serve para o dono <b>reconhecer o lugar</b> —
 * e ele que substitui a ideia antiga de comparar com o CEP e desconfiar em silencio.</p>
 */
public record ReverseGeocodeResponse(String street,
                                     String number,
                                     String neighborhood,
                                     String city,
                                     String state,
                                     String zipCode,
                                     String displayName,
                                     Double latitude,
                                     Double longitude) {

    /**
     * A coordenada volta na resposta <b>exatamente como entrou</b>, nao a que o servico devolveria
     * para o endereco encontrado. Ela e o dado preciso aqui (o dono esta em pe no lugar); o texto e
     * que e aproximado. Sao fontes diferentes e ambas ficam.
     */
    public static ReverseGeocodeResponse from(ReverseGeocodeResult result, double latitude, double longitude) {
        return new ReverseGeocodeResponse(
                result.street(),
                result.number(),
                result.neighborhood(),
                result.city(),
                result.state(),
                result.zipCode(),
                result.displayName(),
                latitude,
                longitude);
    }
}
