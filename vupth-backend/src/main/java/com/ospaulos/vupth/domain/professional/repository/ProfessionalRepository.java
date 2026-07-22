package com.ospaulos.vupth.domain.professional.repository;

import com.ospaulos.vupth.domain.professional.model.Professional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ProfessionalRepository extends JpaRepository<Professional, UUID> {

    // Find by specific professional data
    List<Professional> findByIsAvailableTrue();
    List<Professional> findBySpecialtyContainingIgnoreCase(String specialty);

    // Find profissional por establishment
    List<Professional> findByEstablishmentId(UUID establishmentId);

    @Query("""
        SELECT DISTINCT p FROM Professional p
        JOIN FETCH p.baseUser
        LEFT JOIN FETCH p.services
        WHERE p.establishment.id = :establishmentId
        """)
    List<Professional> findByEstablishmentIdWithServices(@Param("establishmentId") UUID establishmentId);

    // Find available professionals in an establishment
    @Query("SELECT p FROM Professional p WHERE p.establishment.id = :establishmentId AND p.isAvailable = true")
    List<Professional> findAvailableByEstablishmentId(@Param("establishmentId") UUID establishmentId);

    // Find profissionais ativos (combinando dados do BaseUser e Professional)
    @Query("SELECT p FROM Professional p WHERE p.baseUser.isActive = true AND p.isAvailable = true")
    List<Professional> findActiveProfessionals();

    // Find by specialty in a specific establishment
    @Query("SELECT p FROM Professional p WHERE p.establishment.id = :establishmentId AND p.specialty = :specialty")
    List<Professional> findByEstablishmentIdAndSpecialty(@Param("establishmentId") UUID establishmentId, @Param("specialty") String specialty);

    // Check if professional is active and available
    @Query("SELECT COUNT(p) > 0 FROM Professional p WHERE p.id = :id AND p.baseUser.isActive = true AND p.isAvailable = true")
    boolean isActiveAndAvailable(@Param("id") UUID id);

    // Find professional with User eagerly loaded — usar sempre que getName() for chamado
    @Query("SELECT p FROM Professional p JOIN FETCH p.baseUser WHERE p.id = :id")
    Optional<Professional> findByIdWithBaseUser(@Param("id") UUID id);

    @Query("SELECT p FROM Professional p JOIN FETCH p.baseUser LEFT JOIN FETCH p.establishment WHERE p.id = :id")
    Optional<Professional> findByIdWithBaseUserAndEstablishment(@Param("id") UUID id);

    // S1 fatia 2 — ∃ prof afiliado "presente" (WORKING/ON_BREAK)? Query direta (nao a colecao lazy
    // inversa, que fica stale quando a afiliacao muda na mesma transacao); flush AUTO garante o dado.
    boolean existsByEstablishment_IdAndWorkStatusIn(
            UUID establishmentId,
            java.util.Collection<com.ospaulos.vupth.domain.professional.valueObject.ProfessionalStatus> statuses);

    // Busca de profissionais por nome e/ou especialidade. Os parametros ja vem como
    // padroes LIKE prontos ("%" = sem filtro) — nunca null, para evitar problemas de
    // inferencia de tipo do Postgres (null vira bytea e quebra o LOWER). COALESCE
    // trata firstName/specialty nulos. Usado pelo owner para convidar — por isso so
    // retorna CANDIDATOS A CONVITE:
    //   (1) SEM establishment (p.establishment IS NULL): quem ja esta afiliado nao e
    //       candidato (convida-lo daria 409 ALREADY_AFFILIATED);
    //   (2) SEM nenhuma afiliacao PENDING (NOT EXISTS): quem ja tem convite/pedido
    //       pendente (neste ou em outro establishment) nao pode aceitar um novo enquanto
    //       nao resolver o atual (pendencia unica global, #8b) — convida-lo daria
    //       AFFILIATION_ALREADY_PENDING. Some da busca ate resolver.
    @Query("""
        SELECT p FROM Professional p
        JOIN FETCH p.baseUser u
        WHERE LOWER(COALESCE(u.firstName, '')) LIKE LOWER(:nameLike)
          AND LOWER(COALESCE(p.specialty, '')) LIKE LOWER(:specialtyLike)
          AND u.isActive = true
          AND p.establishment IS NULL
          AND NOT EXISTS (
              SELECT 1 FROM AffiliationRequest ar
              WHERE ar.professional = p
                AND ar.status = com.ospaulos.vupth.domain.affiliation.model.AffiliationStatus.PENDING
          )
        ORDER BY u.firstName ASC
        """)
    List<Professional> searchByNameAndSpecialty(
            @Param("nameLike") String nameLike,
            @Param("specialtyLike") String specialtyLike);

}
