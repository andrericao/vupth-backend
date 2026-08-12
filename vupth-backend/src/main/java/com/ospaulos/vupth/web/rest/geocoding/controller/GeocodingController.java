package com.ospaulos.vupth.web.rest.geocoding.controller;

import com.ospaulos.vupth.api.address.dto.response.ReverseGeocodeResponse;
import com.ospaulos.vupth.domain.address.exception.ReverseGeocodeNotFoundException;
import com.ospaulos.vupth.domain.address.valueObject.AddressCoordinates;
import com.ospaulos.vupth.infrastructure.geocoding.Coordinates;
import com.ospaulos.vupth.infrastructure.geocoding.Geocoder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Geocoding reverso (backlog #2, fatia A2) — coordenada do aparelho vira endereco por extenso.
 *
 * <p><b>Fluxo do Partner:</b> o dono esta na loja cadastrando; o app pede a permissao de localizacao
 * explicando que "e assim que sua loja e encontrada", pega o GPS e chama este endpoint. O dono le o
 * endereco, conserta numero/complemento e envia o {@code POST /establishments/{id}/addresses}
 * levando junto a {@code latitude}/{@code longitude} do aparelho.</p>
 *
 * <p><b>Regra central:</b> texto e coordenada vem de fontes diferentes e <b>ambos ficam</b>. Editar o
 * numero na tela NAO move a coordenada, e o endereco que ja chega com coordenada nao e
 * regeocodificado depois.</p>
 *
 * <p><b>Exige autenticacao</b> (cai no {@code anyRequest().authenticated()}), sem exigir role: quem
 * cadastra a primeira loja ainda pode nao ter {@code ROLE_OWNER}. O login e o que segura o consumo do
 * Nominatim, que e gratuito mas tem policy de 1 req/s.</p>
 */
@Slf4j
@RequiredArgsConstructor
@RestController
@RequestMapping("/geocoding")
public class GeocodingController {

    private final Geocoder geocoder;

    @GetMapping("/reverse")
    public ResponseEntity<ReverseGeocodeResponse> reverse(
            @RequestParam("lat") Double lat,
            @RequestParam("lng") Double lng) {

        log.info("CONTROLLER REVERSE GEOCODE — lat={} lng={}", lat, lng);

        AddressCoordinates.validate(lat, lng);

        return geocoder.reverse(new Coordinates(lat, lng))
                .map(result -> ResponseEntity.ok(ReverseGeocodeResponse.from(result, lat, lng)))
                // O front trata "nao ha endereco ali" e "servico fora do ar" do mesmo jeito: deixa o
                // dono digitar na mao. A coordenada continua valida e vai no cadastro do mesmo jeito.
                .orElseThrow(() -> new ReverseGeocodeNotFoundException(
                        "No address found for coordinates " + lat + "," + lng));
    }
}
