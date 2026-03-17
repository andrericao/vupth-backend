package com.ospaulos.vupth.domain.owner.repository;

import com.ospaulos.vupth.domain.establishment.valueObject.EstablishmentStatus;
import com.ospaulos.vupth.domain.owner.model.Owner;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OwnerRepository extends JpaRepository<Owner, UUID> {

    // Find owners ativos
    @Query("SELECT o FROM Owner o WHERE o.baseUser.isActive = true")
    List<Owner> findActiveOwners();

    // Find owners com establishments ativos
    @Query("SELECT DISTINCT o FROM Owner o JOIN o.establishments e WHERE e.status = :status")
    List<Owner> findByEstablishmentStatus(@Param("status") EstablishmentStatus status);

    // Find owner com BaseUser carregado
    @Query("SELECT o FROM Owner o JOIN FETCH o.baseUser WHERE o.id = :id")
    Optional<Owner> findByIdWithBaseUser(@Param("id") UUID id);
}
