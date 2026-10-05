package com.ospaulos.vupth.domain.establishment.service.impl;

import com.ospaulos.vupth.api.address.dto.request.AddressRequest;
import com.ospaulos.vupth.api.address.dto.request.UpdateEstablishmentAddressRequest;
import com.ospaulos.vupth.api.address.mapper.AddressMapper;
import com.ospaulos.vupth.domain.address.model.Address;
import com.ospaulos.vupth.domain.address.service.AddressGeocodingService;
import com.ospaulos.vupth.domain.address.valueObject.AddressCoordinates;
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

        // Fatia A2: a coordenada do aparelho do dono e o caminho principal, entao ela passa a ser
        // conferida na entrada — ponto invalido gravado poe a loja no lugar errado do mapa.
        AddressCoordinates.validate(request.latitude(), request.longitude());

        Establishment establishment = establishmentRepository.findByIdAndOwnerId(establishmentId, ownerId)
                .orElseThrow(() -> new EntityNotFoundException("Establishment not found or not owned by this user"));

        Address address = addressMapper.toAddressEntity(request);
        address.markGpsIfCoordinatesSent(request.latitude(), request.longitude());

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

    /**
     * Correcao do endereco ja cadastrado (backlog #3). Ate aqui so existia o {@code POST} — endereco
     * errado nao tinha conserto pela API, e quem descobre o erro e o dono, quando a loja nao aparece
     * na busca por proximidade.
     */
    @Override
    @Transactional
    public EstablishmentAddressResponse updateAddress(
            UpdateEstablishmentAddressRequest request,
            UUID establishmentId,
            UUID addressId,
            UUID ownerId)
    {
        log.info("SERVICE UPDATE ESTABLISHMENT ADDRESS — establishment={} address={} owner={}",
                establishmentId, addressId, ownerId);

        AddressCoordinates.validate(request.latitude(), request.longitude());

        // A posse e checada aqui, nao no SecurityConfig: dono de uma loja nao pode editar o endereco
        // de outra, e a role sozinha nao diz de quem e a loja.
        establishmentRepository.findByIdAndOwnerId(establishmentId, ownerId)
                .orElseThrow(() -> new EntityNotFoundException("Establishment not found or not owned by this user"));

        EstablishmentAddress estAddress = establishmentAddressRepository
                .findByEstablishmentIdAndAddressId(establishmentId, addressId)
                .orElseThrow(() -> new EntityNotFoundException("Address not found for this establishment"));

        estAddress.getAddress().update(request);
        // A correcao e uma nova chance: ate o worker responder, o dono ve PENDING, nao o UNRESOLVED antigo.
        estAddress.getAddress().setGeocodingFailed(false);

        EstablishmentAddress saved = establishmentAddressRepository.save(estAddress);

        // Retentativa do geocoding — e o conserto que este endpoint existe para permitir: quem
        // cadastrou com o CEP errado (ou quando o servico estava fora do ar) ficou sem coordenada e
        // invisivel na busca por raio, e corrigir o texto tem de dar uma nova chance. Nao ha risco de
        // sobrescrever: o worker ignora endereco que JA tem coordenada, entao quem mandou a lat/lng do
        // aparelho continua com a dele intacta.
        addressGeocodingService.geocodeAfterCommit(addressId);

        return EstablishmentAddressResponse.fromEntity(saved);
    }
}
