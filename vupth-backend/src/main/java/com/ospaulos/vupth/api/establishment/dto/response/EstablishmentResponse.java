package com.ospaulos.vupth.api.establishment.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.ospaulos.vupth.api.address.dto.response.AddressResponse;
import com.ospaulos.vupth.domain.establishment.model.Establishment;
import com.ospaulos.vupth.domain.establishment.valueObject.ServiceType;
import jakarta.validation.constraints.NotNull;

import java.time.OffsetTime;
import java.util.Set;
import java.util.UUID;

public record EstablishmentResponse(
        @NotNull UUID id,
        @NotNull Set<ServiceType> services,
        @NotNull String name,
        ServiceType serviceType,
        String urlImage,
        @NotNull UUID ownerId,

        String phone,
        String instagram,

        @JsonProperty("opening_time")
        OffsetTime openingTime,

        @JsonProperty("closing_time")
        OffsetTime closingTime,

        // Menor tempo de espera (min) entre os profissionais da loja, em minutos.
        // null quando a loja nao tem profissional com servico (sem disponibilidade).
        // Preenchido apenas na busca/listagem publica (GET /establishment).
        @JsonProperty("estimated_wait_minutes")
        Integer estimatedWaitMinutes,

        @JsonProperty("open_status")
        String openStatus,

        // "Aberto agora" efetivo (BUG5 parte B): open_status == OPEN E dentro de
        // [opening_time, closing_time). Campo de LEITURA derivado — nao muda o
        // open_status persistido. O front-client usa pra nao mostrar como aberta
        // uma loja OPEN cujo horario ja passou.
        @JsonProperty("is_open")
        boolean isOpen,

        @JsonProperty("status")
        String status,

        // Endereco principal da loja (Partner BUG4). null quando a loja ainda nao tem endereco.
        // O front le data.address para exibir localizacao no read-back do establishment.
        AddressResponse address
) {
    public static EstablishmentResponse fromEntity(Establishment establishment) {
        return fromEntity(establishment, null);
    }

    public static EstablishmentResponse fromEntity(Establishment establishment, Integer estimatedWaitMinutes) {
        return new EstablishmentResponse(
                establishment.getId(),
                establishment.getServices() != null ? Set.copyOf(establishment.getServices()) : Set.of(),
                establishment.getName(),
                establishment.getServiceType(),
                establishment.getUrlImage(),
                establishment.getOwner().getId(),
                establishment.getPhone(),
                establishment.getInstagram(),
                establishment.getOpeningTime(),
                establishment.getClosingTime(),
                estimatedWaitMinutes,
                establishment.getOpenStatus() != null ? establishment.getOpenStatus().name() : null,
                establishment.isOpenNow(),
                establishment.getStatus() != null ? establishment.getStatus().name() : null,
                AddressResponse.fromEntity(establishment.getMainAddress())
        );
    }
}
