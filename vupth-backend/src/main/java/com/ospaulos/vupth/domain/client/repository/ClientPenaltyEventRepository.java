package com.ospaulos.vupth.domain.client.repository;

import com.ospaulos.vupth.domain.client.model.ClientPenaltyEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface ClientPenaltyEventRepository extends JpaRepository<ClientPenaltyEvent, UUID> {

    List<ClientPenaltyEvent> findByClientIdOrderByCreatedAtDesc(UUID clientId);

    long countByClientId(UUID clientId);

    /** Multas com valor (D4) do cliente — pendentes, em cobranca ou pagas; quem filtra e o servico. */
    List<ClientPenaltyEvent> findByClientIdAndAmountIsNotNullOrderByCreatedAtAsc(UUID clientId);

    /** Este atendimento ja gerou uma multa deste tipo? (quem pagou o 3o atraso sai sem nova multa) */
    boolean existsByAttendanceIdAndType(UUID attendanceId, com.ospaulos.vupth.domain.client.model.PenaltyType type);

    /** Multas carregadas por estes atendimentos (a decomposicao do relatorio). */
    List<ClientPenaltyEvent> findByChargedAttendanceIdInOrderByCreatedAtAsc(Collection<UUID> attendanceIds);
}
