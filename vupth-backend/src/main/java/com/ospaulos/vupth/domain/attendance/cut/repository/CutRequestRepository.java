package com.ospaulos.vupth.domain.attendance.cut.repository;

import com.ospaulos.vupth.domain.attendance.cut.CutRequestStatus;
import com.ospaulos.vupth.domain.attendance.cut.model.CutRequest;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public interface CutRequestRepository extends JpaRepository<CutRequest, UUID> {

    /**
     * Pedidos de um profissional num dado estado (ex.: PENDING para a tela do Partner).
     *
     * <p>O grafo traz {@code client.baseUser} junto porque o read-model expoe {@code client_name},
     * e {@code Client.getFirstName()} le do {@code BaseUser} (LAZY, {@code @MapsId}) — sem isso
     * seriam dois SELECTs por linha da lista.
     */
    @EntityGraph(attributePaths = {"client", "client.baseUser"})
    List<CutRequest> findByProfessionalIdAndStatusOrderByCreatedAtAsc(UUID professionalId, CutRequestStatus status);

    /** Pedidos PENDING ja vencidos — base do auto-nega por timer (fatia 3). */
    @Query("SELECT c FROM CutRequest c WHERE c.status = :status AND c.expiresAt <= :now")
    List<CutRequest> findByStatusAndExpiresAtBefore(@Param("status") CutRequestStatus status,
                                                    @Param("now") OffsetDateTime now);

    /**
     * "Cabeca" da fila de expiracao = o PENDING que vence primeiro. E o unico horario que o timer
     * do {@code CutRequestExpiryScheduler} precisa saber para (re)armar o despertador. Vazio = ocioso.
     * Sem os de {@code expiresAt} nulo (pedido para outro dia, DEC-27): esses nao vencem.
     */
    java.util.Optional<CutRequest> findFirstByStatusAndExpiresAtIsNotNullOrderByExpiresAtAsc(CutRequestStatus status);

    /** Carrega com os serviceItems (para o aceite montar o Attendance sem LazyInit). */
    @Query("SELECT c FROM CutRequest c LEFT JOIN FETCH c.serviceItems WHERE c.id = :id")
    java.util.Optional<CutRequest> findByIdEager(@Param("id") UUID id);
}
