package com.ospaulos.vupth.domain.serviceItem.repository;

import com.ospaulos.vupth.domain.serviceItem.model.ServiceItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ServiceItemRepository extends JpaRepository<ServiceItem, UUID> {

    @Query("""
        SELECT s FROM ServiceItem s
        WHERE s.professional.establishment.id = :establishmentId
        """)
    List<ServiceItem> findAllByEstablishmentId(@Param("establishmentId") UUID establishmentId);

    @Query("""
        SELECT s FROM ServiceItem s
        JOIN FETCH s.professional p
        JOIN FETCH p.establishment
        WHERE p.id = :professionalId
        """)
    List<ServiceItem> findByProfessionalId(@Param("professionalId") UUID professionalId);
}
