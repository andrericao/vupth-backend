package com.ospaulos.vupth.domain.attendance.cut.repository;

import com.ospaulos.vupth.domain.attendance.cut.CutRequestStatus;
import com.ospaulos.vupth.domain.attendance.cut.model.CutRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public interface CutRequestRepository extends JpaRepository<CutRequest, UUID> {

    /** Pedidos de um profissional num dado estado (ex.: PENDING para a tela do Partner). */
    List<CutRequest> findByProfessionalIdAndStatusOrderByCreatedAtAsc(UUID professionalId, CutRequestStatus status);

    /** Pedidos PENDING ja vencidos — base do auto-nega por timer (fatia 3). */
    @Query("SELECT c FROM CutRequest c WHERE c.status = :status AND c.expiresAt <= :now")
    List<CutRequest> findByStatusAndExpiresAtBefore(@Param("status") CutRequestStatus status,
                                                    @Param("now") OffsetDateTime now);

    /**
     * "Cabeca" da fila de expiracao = o PENDING que vence primeiro. E o unico horario que o timer
     * do {@code CutRequestExpiryScheduler} precisa saber para (re)armar o despertador. Vazio = ocioso.
     */
    java.util.Optional<CutRequest> findFirstByStatusOrderByExpiresAtAsc(CutRequestStatus status);

    /** Carrega com os serviceItems (para o aceite montar o Attendance sem LazyInit). */
    @Query("SELECT c FROM CutRequest c LEFT JOIN FETCH c.serviceItems WHERE c.id = :id")
    java.util.Optional<CutRequest> findByIdEager(@Param("id") UUID id);
}
