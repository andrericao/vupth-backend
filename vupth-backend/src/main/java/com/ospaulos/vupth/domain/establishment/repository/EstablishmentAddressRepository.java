package com.ospaulos.vupth.domain.establishment.repository;

import com.ospaulos.vupth.domain.establishment.model.EstablishmentAddress;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface EstablishmentAddressRepository extends JpaRepository<EstablishmentAddress, UUID> {
    List<EstablishmentAddress> findAllByEstablishmentId(UUID establishmentId);
}
