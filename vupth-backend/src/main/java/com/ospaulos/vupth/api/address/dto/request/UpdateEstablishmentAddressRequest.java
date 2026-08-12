package com.ospaulos.vupth.api.address.dto.request;

import jakarta.validation.constraints.Pattern;

/**
 * Correcao do endereco do estabelecimento (backlog #3). <b>Todo campo e opcional</b>: {@code null}
 * significa "nao mexe", entao o dono manda so o que esta errado.
 *
 * <p>Existe porque ate aqui so havia o {@code POST} — endereco cadastrado errado nao tinha conserto
 * pela API, e o dono e justamente quem descobre o erro, quando a loja nao aparece na busca por
 * proximidade.</p>
 *
 * <p><b>Nao reaproveita o {@code UpdateAddressRequest}</b> (o do endereco de usuario) de proposito:
 * aquele exige um {@code userAddressId} que aqui nao existe e valida o CEP com mascara de hifen,
 * enquanto a base guarda 8 digitos puros.</p>
 *
 * <p><b>latitude/longitude:</b> so mudam se vierem no corpo — editar o texto NAO move o ponto no
 * mapa. Sao fontes diferentes (o texto e digitado, a coordenada vem do GPS ou do geocoding) e as
 * duas ficam. Ver a fatia A2 do GPS.</p>
 */
public record UpdateEstablishmentAddressRequest(

        String street,

        String number,

        String complement,

        String neighborhood,

        String city,

        String state,

        @Pattern(
                regexp = "^\\d{8}$",
                message = "Invalid ZIP code. Send only 8 digits, no punctuation (e.g. 01310100)"
        )
        String zipCode,

        String reference,

        Double latitude,

        Double longitude
) {}
