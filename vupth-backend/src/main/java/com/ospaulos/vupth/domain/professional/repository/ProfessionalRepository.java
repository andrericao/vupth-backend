package com.ospaulos.vupth.domain.professional.repository;

import com.ospaulos.vupth.domain.professional.model.Professional;
import com.ospaulos.vupth.domain.professional.valueObject.ProfessionalStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.Collection;
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

    /**
     * Profissionais COM ao menos um servico (#31), de VARIAS lojas de uma vez — a primeira das
     * quatro consultas do resumo em lote da listagem publica (B10).
     *
     * <p>Devolve so os dois ids ({@code [establishmentId, professionalId]}) porque e tudo o que o
     * resumo precisa: quem entra na contagem de {@code available_professionals} e de quem se busca
     * a fila. Trazer a entidade aqui reintroduziria o custo que este caminho existe para tirar —
     * {@code baseUser} e {@code services} inteiros para extrair dois numeros.</p>
     */
    @Query("""
        SELECT p.establishment.id, p.id
        FROM Professional p
        WHERE p.establishment.id IN :establishmentIds
          AND SIZE(p.services) > 0
        """)
    List<Object[]> findWithServiceByEstablishmentIds(
            @Param("establishmentIds") Collection<UUID> establishmentIds);

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
            Collection<ProfessionalStatus> statuses);

    // Fechar a loja leva todos a OFF (Andre, 18/09): os que estao em expediente agora.
    List<Professional> findByEstablishment_IdAndWorkStatusIn(
            UUID establishmentId,
            Collection<ProfessionalStatus> statuses);

    // --- S1 fatia 5 (auto-fechamento por horario / BUG1) -----------------------------------------

    // Profissionais em um dado conjunto de work_status — usado pelo auto-fechamento para varrer quem
    // esta em expediente (WORKING/ON_BREAK) no disparo do timer (fonte da verdade = a query, nao o
    // palpite do timer; mesmo principio do IntentionPromotionScheduler).
    List<Professional> findByWorkStatusIn(Collection<ProfessionalStatus> statuses);

    // Carencias ja vencidas (closing_grace_until <= agora) — o disparo aplica o desfecho de cada uma
    // (auto-OFF / drenagem / auto-correcao). Ignora quem esta com carencia NULL (sem carencia armada).
    List<Professional> findByClosingGraceUntilLessThanEqual(OffsetDateTime instant);

    // "Cabeca" das carencias pendentes (menor closing_grace_until) — alimenta o proximo disparo do
    // timer de auto-fechamento (analogo a findEarliestIntention / findFirstByStatusOrderByExpiresAtAsc).
    Optional<Professional> findFirstByClosingGraceUntilIsNotNullOrderByClosingGraceUntilAsc();

    // Busca de profissionais por nome (ou apelido, BAK-4) e/ou especialidade. Os parametros ja vem como
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
        WHERE (LOWER(COALESCE(u.firstName, '')) LIKE LOWER(:nameLike)
               OR LOWER(COALESCE(u.nickname, '')) LIKE LOWER(:nameLike))
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
