package com.ospaulos.vupth.api.client.mapper;

import com.ospaulos.vupth.api.client.dto.request.ClientRequest;
import com.ospaulos.vupth.api.client.dto.response.ClientResponse;
import com.ospaulos.vupth.domain.client.model.Client;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface ClientMapper {

    Client toClientEntity(ClientRequest request);

    ClientResponse toGeneralInfosClientResponse(Client client);
}
