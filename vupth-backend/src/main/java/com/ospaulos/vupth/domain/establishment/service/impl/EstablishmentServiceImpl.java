package com.ospaulos.vupth.domain.establishment.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ospaulos.vupth.api.establishment.dto.request.EstablishmentRequest;
import com.ospaulos.vupth.api.establishment.dto.response.EstablishmentResponse;
import com.ospaulos.vupth.domain.establishment.exception.EstablishmentNotFoundException;
import com.ospaulos.vupth.domain.establishment.model.Establishment;
import com.ospaulos.vupth.domain.establishment.service.EstablishmentService;
import com.ospaulos.vupth.domain.owner.model.Owner;
import com.ospaulos.vupth.domain.serviceItem.model.ServiceItem;
import com.ospaulos.vupth.domain.establishment.repository.EstablishmentRepository;
import com.ospaulos.vupth.domain.owner.repository.OwnerRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@AllArgsConstructor
@org.springframework.transaction.annotation.Transactional
public class EstablishmentServiceImpl implements EstablishmentService {

    private final OwnerRepository ownerRepository;
    private final EstablishmentRepository establishmentRepository;
    private final ObjectMapper mapper;

    @Override
    public EstablishmentResponse registerEstablishment(EstablishmentRequest request){

        log.info("SERVICE CREATING ESTABLISHMENT " + request.name() );

        Owner owner = ownerRepository.findById(request.ownerID()).orElseThrow();

        Establishment establishment = new Establishment();

        establishment.setServices(request.services());
        establishment.setName(request.name());
        establishment.setUrlImage(request.urlImage());
        establishment.setStatus(request.status());
        establishment.setOpenStatus(request.openStatus());
        establishment.setOwner(owner);

        Establishment establishmentCreated = this.establishmentRepository.save(establishment);

        return EstablishmentResponse.fromEntity(establishmentCreated);
    }

    @Override
    public List<EstablishmentResponse> findAll() {
        return establishmentRepository.findAllWithServices().stream()
                .map(EstablishmentResponse::fromEntity)
                .toList();
    }

    @Override
    public EstablishmentResponse findById(UUID id){
         Establishment establishment = establishmentRepository.findByIdWithServices(id)
                .orElseThrow(() -> new EstablishmentNotFoundException(id));

         return EstablishmentResponse.fromEntity(establishment);
    }

    @Override
    public List<ServiceItem> getServicesItemByEstablishment(UUID establishmentId){

        Establishment establishment = establishmentRepository.findById(establishmentId)
                .orElseThrow(() -> new EntityNotFoundException("Establishment not found"));

        return establishment.getServicesItemByEstablishment();
    }
}