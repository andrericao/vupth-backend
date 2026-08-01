package com.ospaulos.vupth.infrastructure.geocoding;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Geocoder desligado ({@code vupth.geocoding.provider=none}) — mesmo padrao do {@code NoOpPushSender}
 * e do {@code NoOpEmailSender}. Ativo nos <b>testes</b>, onde nenhuma suite pode depender de rede: o
 * endereco simplesmente nasce sem coordenada, que e exatamente o cenario "ainda nao geocodificado"
 * que a busca por raio tem de tolerar.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "vupth.geocoding", name = "provider", havingValue = "none")
public class NoOpGeocoder implements Geocoder {

    @Override
    public Optional<Coordinates> geocode(GeocodeQuery query) {
        log.debug("[Geocoding] provider=none — endereco nao sera geocodificado (cep={})",
                query == null ? null : query.zipCode());
        return Optional.empty();
    }
}
