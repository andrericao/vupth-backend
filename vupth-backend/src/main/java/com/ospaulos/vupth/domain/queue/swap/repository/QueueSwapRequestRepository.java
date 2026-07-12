package com.ospaulos.vupth.domain.queue.swap.repository;

import com.ospaulos.vupth.domain.queue.swap.SwapRequestStatus;
import com.ospaulos.vupth.domain.queue.swap.model.QueueSwapRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface QueueSwapRequestRepository extends JpaRepository<QueueSwapRequest, UUID> {

    /**
     * Ja existe uma proposta PENDING entre estes dois atendimentos (em qualquer direcao)?
     * Evita propostas duplicadas para o mesmo par.
     */
    @Query("""
            SELECT COUNT(s) > 0 FROM QueueSwapRequest s
            WHERE s.status = :status
              AND ( (s.proposer.id = :a AND s.target.id = :b)
                 OR (s.proposer.id = :b AND s.target.id = :a) )
            """)
    boolean existsPendingBetween(@Param("a") UUID a, @Param("b") UUID b,
                                 @Param("status") SwapRequestStatus status);

    /** Propostas com um dado status que envolvem (como proposer OU target) um atendimento. */
    @Query("""
            SELECT s FROM QueueSwapRequest s
            WHERE s.status = :status
              AND (s.proposer.id = :attendanceId OR s.target.id = :attendanceId)
            """)
    List<QueueSwapRequest> findByStatusInvolvingAttendance(@Param("attendanceId") UUID attendanceId,
                                                           @Param("status") SwapRequestStatus status);

    /** Propostas com um dado status que envolvem (como proposer OU target) um cliente. */
    @Query("""
            SELECT s FROM QueueSwapRequest s
            WHERE s.status = :status
              AND (s.proposer.client.id = :clientId OR s.target.client.id = :clientId)
            ORDER BY s.createdAt DESC
            """)
    List<QueueSwapRequest> findByStatusInvolvingClient(@Param("clientId") UUID clientId,
                                                       @Param("status") SwapRequestStatus status);

    /** Carrega a proposta com proposer/target (e seus clientes) materializados para resposta/troca. */
    @Query("""
            SELECT s FROM QueueSwapRequest s
            JOIN FETCH s.proposer p
            JOIN FETCH s.target t
            WHERE s.id = :id
            """)
    Optional<QueueSwapRequest> findByIdEager(@Param("id") UUID id);
}
