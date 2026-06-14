package com.ospaulos.vupth.domain.establishment.repository;

import com.ospaulos.vupth.domain.establishment.model.Establishment;
import com.ospaulos.vupth.domain.establishment.valueObject.EstablishmentStatus;
import com.ospaulos.vupth.domain.establishment.valueObject.ServiceType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface EstablishmentRepository extends JpaRepository<Establishment, UUID> {

    @Query("SELECT e FROM Establishment e LEFT JOIN FETCH e.addresses WHERE e.id = :id")
    Optional<Establishment> findByIdWithAddresses(@Param("id") UUID id);

    @Query("""
    SELECT e
    FROM Establishment e
    JOIN FETCH e.owner o
    LEFT JOIN FETCH e.services
    WHERE e.id = :establishmentId
      AND o.id = :ownerId
    """)
    Optional<Establishment> findByIdAndOwnerId(UUID establishmentId, UUID ownerId);

    @Query("""
    SELECT e FROM Establishment e
    LEFT JOIN FETCH e.services
    LEFT JOIN FETCH e.owner
    WHERE e.id = :id
""")
    Optional<Establishment> findByIdEager(@Param("id") UUID id);

    @Query("""
    SELECT e FROM Establishment e
    LEFT JOIN FETCH e.services
    LEFT JOIN FETCH e.owner
    WHERE e.owner.id = :ownerId
      AND e.status <> 'INACTIVE'
    ORDER BY e.createdAt DESC
    """)
    List<Establishment> findByOwnerId(@Param("ownerId") UUID ownerId);

    // Lista todos os ativos (exclui INACTIVE) com associations para serialização
    @Query("""
        SELECT e FROM Establishment e
        LEFT JOIN FETCH e.services
        LEFT JOIN FETCH e.owner
        WHERE e.status <> 'INACTIVE'
        ORDER BY e.createdAt DESC
        """)
    List<Establishment> findAllActive();

    // Busca por nome (case-insensitive, parcial) excluindo INACTIVE
    @Query("""
        SELECT e FROM Establishment e
        LEFT JOIN FETCH e.services
        LEFT JOIN FETCH e.owner
        WHERE LOWER(e.name) LIKE LOWER(CONCAT('%', :name, '%'))
          AND e.status <> 'INACTIVE'
        ORDER BY e.createdAt DESC
        """)
    List<Establishment> findByNameContainingIgnoreCaseAndStatusNot(
            @Param("name") String name);

    // Filtra por ServiceType excluindo INACTIVE
    @Query("""
        SELECT DISTINCT e FROM Establishment e
        LEFT JOIN FETCH e.services s
        LEFT JOIN FETCH e.owner
        WHERE :serviceType MEMBER OF e.services
          AND e.status <> 'INACTIVE'
        ORDER BY e.createdAt DESC
        """)
    List<Establishment> findActiveByServiceType(
            @Param("serviceType") ServiceType serviceType);

    // Filtra por bairro (case-insensitive, parcial) excluindo INACTIVE.
    // JOIN (nao LEFT JOIN) em addresses: so retorna quem tem endereco no bairro.
    @Query("""
        SELECT DISTINCT e FROM Establishment e
        LEFT JOIN FETCH e.services
        LEFT JOIN FETCH e.owner
        JOIN e.addresses ea
        JOIN ea.address a
        WHERE LOWER(a.neighborhood) LIKE LOWER(CONCAT('%', :neighborhood, '%'))
          AND e.status <> 'INACTIVE'
        ORDER BY e.createdAt DESC
        """)
    List<Establishment> findActiveByNeighborhood(
            @Param("neighborhood") String neighborhood);

    // Combina ServiceType + bairro (caso de uso "barbearia no Centro") excluindo INACTIVE.
    @Query("""
        SELECT DISTINCT e FROM Establishment e
        LEFT JOIN FETCH e.services
        LEFT JOIN FETCH e.owner
        JOIN e.addresses ea
        JOIN ea.address a
        WHERE :serviceType MEMBER OF e.services
          AND LOWER(a.neighborhood) LIKE LOWER(CONCAT('%', :neighborhood, '%'))
          AND e.status <> 'INACTIVE'
        ORDER BY e.createdAt DESC
        """)
    List<Establishment> findActiveByServiceTypeAndNeighborhood(
            @Param("serviceType") ServiceType serviceType,
            @Param("neighborhood") String neighborhood);
}
