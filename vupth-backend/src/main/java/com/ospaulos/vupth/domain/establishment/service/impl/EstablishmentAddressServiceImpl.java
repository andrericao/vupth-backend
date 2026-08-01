package com.ospaulos.vupth.domain.establishment.service.impl;

import com.ospaulos.vupth.api.address.dto.request.AddressRequest;
import com.ospaulos.vupth.api.address.mapper.AddressMapper;
import com.ospaulos.vupth.domain.address.model.Address;
import com.ospaulos.vupth.domain.address.service.AddressGeocodingService;
import com.ospaulos.vupth.domain.establishment.model.Establishment;
import com.ospaulos.vupth.domain.establishment.model.EstablishmentAddress;
import com.ospaulos.vupth.domain.establishment.repository.EstablishmentAddressRepository;
import com.ospaulos.vupth.domain.establishment.repository.EstablishmentRepository;
import com.ospaulos.vupth.domain.establishment.service.EstablishmentAddressService;
import com.ospaulos.vupth.api.address.dto.response.EstablishmentAddressResponse;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class EstablishmentAddressServiceImpl implements EstablishmentAddressService {

    private final EstablishmentRepository establishmentRepository;
    private final EstablishmentAddressRepository establishmentAddressRepository;
    private final AddressMapper addressMapper;
    private final AddressGeocodingService addressGeocodingService;

    @Override
    @Transactional
    public EstablishmentAddressResponse registerAddress(
            AddressRequest request,
            UUID establishmentId,
            UUID ownerId)
    {
        log.info("SERVICE CREATE ESTABLISHMENT ADDRESS — establishment={} owner={}", establishmentId, ownerId);

        Establishment establishment = establishmentRepository.findByIdAndOwnerId(establishmentId, ownerId)
                .orElseThrow(() -> new EntityNotFoundException("Establishment not found or not owned by this user"));

        Address address = addressMapper.toAddressEntity(request);

        EstablishmentAddress estAddress = new EstablishmentAddress(address);
        estAddress.setEstablishment(establishment);

        // Salva EstablishmentAddress diretamente — Address é salvo via cascade=ALL em estAddress.address.
        // Evita o cascade problemático de establishmentRepository.save(establishment),
        // que tentaria mergear todos os professionals (com lazy baseUser) desnecessariamente.
        EstablishmentAddress saved = establishmentAddressRepository.save(estAddress);

        log.info("SERVICE CREATE ESTABLISHMENT ADDRESS — saved id={}", saved.getId());

        // Backlog #2 — resolve latitude/longitude em thread de fundo, APOS o commit: o dono nao espera
        // a chamada externa e o geocoder fora do ar nao impede o cadastro. Sem coordenada, o
        // estabelecimento so nao aparece na busca por raio ate ser resolvido.
        addressGeocodingService.geocodeAfterCommit(saved.getAddress().getId());

        return EstablishmentAddressResponse.fromEntity(saved);
    }
}
