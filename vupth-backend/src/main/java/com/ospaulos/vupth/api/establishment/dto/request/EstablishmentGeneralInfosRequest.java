package com.ospaulos.vupth.api.establishment.dto.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.ospaulos.vupth.domain.establishment.valueObject.ServiceType;

import java.time.OffsetTime;
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

        // Horario de funcionamento (#11g2). Ex.: "09:00Z". null preserva (semantica PATCH).
        @JsonProperty("opening_time")
        OffsetTime openingTime,

        @JsonProperty("closing_time")
        OffsetTime closingTime
) {}
