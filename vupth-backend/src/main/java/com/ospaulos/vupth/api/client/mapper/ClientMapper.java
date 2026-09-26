package com.ospaulos.vupth.api.client.mapper;

import com.ospaulos.vupth.api.client.dto.request.ClientRequest;
import com.ospaulos.vupth.api.client.dto.response.ClientResponse;
import com.ospaulos.vupth.domain.client.model.Client;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface ClientMapper {

    Client toClientEntity(ClientRequest request);

    // O strike saiu (Andre, 26/09) — nunca foi regra combinada. strikeCount segue no contrato fixo em 0
    // ate os apps tirarem; depois sai.
    @Mapping(target = "strikeCount", constant = "0")
    ClientResponse toGeneralInfosClientResponse(Client client);
}
