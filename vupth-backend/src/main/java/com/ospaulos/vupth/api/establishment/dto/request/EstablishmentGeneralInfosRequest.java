package com.ospaulos.vupth.api.establishment.dto.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.ospaulos.vupth.domain.establishment.valueObject.ServiceType;

import java.time.LocalTime;
import java.util.Set;

@JsonIgnoreProperties(ignoreUnknown = false)
public record EstablishmentGeneralInfosRequest(
        String name,

        @JsonProperty("url_image")
        String urlImage,

        @JsonProperty("service_type")
        ServiceType serviceType,

        Set<ServiceType> services,

        String phone,

        String instagram,

        // Horario de funcionamento (#11g2). Relogio de PAREDE no fuso da loja: "09:00" (BUG2b —
        // sufixo de offset legado "09:00Z"/"09:00-03:00" e aceito e IGNORADO, os digitos e que
        // valem). null preserva (semantica PATCH).
        @JsonProperty("opening_time")
        LocalTime openingTime,

        @JsonProperty("closing_time")
        LocalTime closingTime
) {}
