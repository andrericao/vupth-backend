package com.ospaulos.vupth.api.establishment.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.ospaulos.vupth.api.address.dto.response.AddressResponse;
import com.ospaulos.vupth.domain.establishment.model.Establishment;
import com.ospaulos.vupth.domain.establishment.valueObject.ServiceType;
import jakarta.validation.constraints.NotNull;

import java.time.LocalTime;
import java.util.Set;
import java.util.UUID;

public record EstablishmentResponse(
        @NotNull UUID id,
        @NotNull Set<ServiceType> services,
        @NotNull String name,
        ServiceType serviceType,
        String urlImage,
        // Nullable desde "apagar a conta" (S19): quando o dono apaga a conta, a loja sobrevive
        // SEM dono (os atendimentos dela pertencem tambem aos clientes e aos profissionais).
        // Era @NotNull, e o mapper fazia getOwner().getId() cru — bastava uma loja sem dono para
        // a listagem publica inteira responder 500.
        UUID ownerId,

        String phone,
        String instagram,

        @JsonProperty("opening_time")
        LocalTime openingTime,

        @JsonProperty("closing_time")
        LocalTime closingTime,

        // Menor tempo de espera (min) entre os profissionais da loja, em minutos.
        // null quando a loja nao tem profissional com servico (sem disponibilidade).
        // Preenchido apenas na busca/listagem publica (GET /establishment).
        @JsonProperty("estimated_wait_minutes")
        Integer estimatedWaitMinutes,

        // Quantidade de profissionais afiliados COM ao menos um servico (mesmo
        // conjunto que alimenta estimated_wait_minutes). null fora da listagem publica.
        // O front-client usa pra decidir se mostra uma loja FECHADA na Home: so exibe
        // as fechadas com available_professionals > 0 (ha profissional pra agendar a
        // reabertura). Independe de open_status e de o profissional estar ocupado agora.
        @JsonProperty("available_professionals")
        Integer availableProfessionals,

        @JsonProperty("open_status")
        String openStatus,

        // "Aberto agora" efetivo. Campo de LEITURA derivado — nao muda o open_status persistido.
        // ⚠️ Desde 19/09 ele e IGUAL a open_status == OPEN: o horario publicado virou o plano do
        // dia (guia auto-fechamento e agenda) e nao fecha mais uma loja que esta atendendo. Ate
        // 18/09 exigia tambem estar dentro de [opening_time, closing_time), e era isso que punha a
        // loja como aberta no app do parceiro e fechada no do cliente, na mesma resposta.
        @JsonProperty("is_open")
        boolean isOpen,

        @JsonProperty("status")
        String status,

        // Endereco principal da loja (Partner BUG4). null quando a loja ainda nao tem endereco.
        // O front le data.address para exibir localizacao no read-back do establishment.
        AddressResponse address,

        // Distancia em km ate a coordenada enviada na busca (GET /establishment?lat=&lng=).
        // Arredondada em 2 casas — e o numero que o card mostra ("2,3 km"), o front NAO recalcula.
        // null em dois casos distintos: (a) a busca nao mandou lat/lng, ou (b) mandou, mas ESTA loja
        // nao tem coordenada — nesse caso ela vai para o FIM da lista em vez de sumir (so some se a
        // busca tambem mandar um radiusKm explicito).
        @JsonProperty("distance_km")
        Double distanceKm
) {
    public static EstablishmentResponse fromEntity(Establishment establishment) {
        return fromEntity(establishment, null, null);
    }

    public static EstablishmentResponse fromEntity(Establishment establishment,
                                                   Integer estimatedWaitMinutes,
                                                   Integer availableProfessionals) {
        return new EstablishmentResponse(
                establishment.getId(),
                establishment.getServices() != null ? Set.copyOf(establishment.getServices()) : Set.of(),
                establishment.getName(),
                establishment.getServiceType(),
                establishment.getUrlImage(),
                establishment.getOwner() != null ? establishment.getOwner().getId() : null,
                establishment.getPhone(),
                establishment.getInstagram(),
                establishment.todayOpens(),
                establishment.todayCloses(),
                estimatedWaitMinutes,
                availableProfessionals,
                establishment.getOpenStatus() != null ? establishment.getOpenStatus().name() : null,
                establishment.isOpenNow(),
                establishment.getStatus() != null ? establishment.getStatus().name() : null,
                AddressResponse.fromEntity(establishment.getMainAddress()),
                null
        );
    }

    /**
     * Copia com a distancia preenchida (fatia B do GPS). Wither em vez de mais um parametro no
     * {@code fromEntity} de proposito: a distancia so existe na listagem publica quando a busca
     * mandou coordenada, e ela e calculada DEPOIS do mapeamento, junto com a ordenacao.
     */
    public EstablishmentResponse withDistanceKm(Double distanceKm) {
        return new EstablishmentResponse(
                id, services, name, serviceType, urlImage, ownerId, phone, instagram,
                openingTime, closingTime, estimatedWaitMinutes, availableProfessionals,
                openStatus, isOpen, status, address, distanceKm);
    }
}
