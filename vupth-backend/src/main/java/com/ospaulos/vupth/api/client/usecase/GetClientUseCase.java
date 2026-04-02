package com.ospaulos.vupth.api.client.usecase;

import com.ospaulos.vupth.api.client.dto.response.ClientResponse;
import com.ospaulos.vupth.api.client.mapper.ClientMapper;
import com.ospaulos.vupth.api.user.exception.UserNotFoundException;
import com.ospaulos.vupth.domain.client.model.Client;
import com.ospaulos.vupth.domain.client.repository.ClientRepository;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class GetClientUseCase {

    private final ClientRepository clientRepository;
    private final ClientMapper mapper;

    public ClientResponse execute(UUID id){

      log.info("Get Client UseCase - BEGIN = " + id);

        Client client = clientRepository.findById(id)
                .orElseThrow(
                        () -> new UserNotFoundException(id)
                );

        return mapper.toGeneralInfosClientResponse(client);
    }


}
