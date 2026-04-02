package com.ospaulos.vupth.domain.establishment.repository;

import com.ospaulos.vupth.domain.establishment.model.Establishment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface EstablishmentRepository extends JpaRepository<Establishment, UUID> {

    @Query("SELECT e FROM Establishment e LEFT JOIN FETCH e.addresses WHERE e.id = :id")
    Optional<Establishment> findByIdWithAddresses(@Param("id") UUID id);

    @Query("""
    SELECT e
    FROM Establishment e
    JOIN FETCH e.owner o
    WHERE e.id = :establishmentId
      AND o.id = :ownerId
    """)
    Optional<Establishment> findByIdAndOwnerId(UUID establishmentId, UUID ownerId);

}
