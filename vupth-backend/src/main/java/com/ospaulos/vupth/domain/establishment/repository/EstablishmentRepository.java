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

    // Read-back do owner (GET /establishment/mine). Mapeado FORA de transacao no controller
    // e com OSIV off em prod, entao o endereco (Partner BUG4) precisa vir por FETCH aqui —
    // senao acessar e.addresses/ea.address estoura LazyInitializationException.
    @Query("""
    SELECT DISTINCT e FROM Establishment e
    LEFT JOIN FETCH e.services
    LEFT JOIN FETCH e.owner
    LEFT JOIN FETCH e.addresses ea
    LEFT JOIN FETCH ea.address
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

    // Busca por nome (sem caixa e sem acento, parcial) excluindo INACTIVE. Casa tambem o nome e o APELIDO
    // dos profissionais da loja (BAK-4 · S13): quem procura "Dedeco" acha a barbearia onde ele atende.
    // :name chega dobrado pelo SearchText.fold; o translate tira da coluna os mesmos acentos (busca-sem-acento).
    @Query("""
        SELECT e FROM Establishment e
        LEFT JOIN FETCH e.services
        LEFT JOIN FETCH e.owner
        WHERE (FUNCTION('translate', LOWER(e.name), 'áàâãäåéèêëíìîïóòôõöúùûüçñýÁÀÂÃÄÅÉÈÊËÍÌÎÏÓÒÔÕÖÚÙÛÜÇÑÝ', 'aaaaaaeeeeiiiiooooouuuucnyaaaaaaeeeeiiiiooooouuuucny') LIKE CONCAT('%', :name, '%')
               OR EXISTS (
                   SELECT 1 FROM Professional p JOIN p.baseUser u
                   WHERE p.establishment = e
                     AND (FUNCTION('translate', LOWER(COALESCE(u.nickname, '')), 'áàâãäåéèêëíìîïóòôõöúùûüçñýÁÀÂÃÄÅÉÈÊËÍÌÎÏÓÒÔÕÖÚÙÛÜÇÑÝ', 'aaaaaaeeeeiiiiooooouuuucnyaaaaaaeeeeiiiiooooouuuucny') LIKE CONCAT('%', :name, '%')
                          OR FUNCTION('translate', LOWER(COALESCE(u.firstName, '')), 'áàâãäåéèêëíìîïóòôõöúùûüçñýÁÀÂÃÄÅÉÈÊËÍÌÎÏÓÒÔÕÖÚÙÛÜÇÑÝ', 'aaaaaaeeeeiiiiooooouuuucnyaaaaaaeeeeiiiiooooouuuucny') LIKE CONCAT('%', :name, '%'))))
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
