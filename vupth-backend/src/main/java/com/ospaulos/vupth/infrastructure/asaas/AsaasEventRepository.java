package com.ospaulos.vupth.infrastructure.asaas;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public interface AsaasEventRepository extends JpaRepository<AsaasEvent, UUID> {

    /** Grava sem explodir se outro pedido gravou o mesmo event_id ao mesmo tempo (webhook repetido). Devolve 1 ou 0. */
    @Modifying
    @Query(value = """
            INSERT INTO asaas_event (id, event_id, event, payment_id, external_reference, value, net_value, status,
                                     billing_type, received_at)
            VALUES (:#{#e.id}, :#{#e.eventId}, :#{#e.event}, :#{#e.paymentId}, :#{#e.externalReference},
                    :#{#e.value}, :#{#e.netValue}, :#{#e.status}, :#{#e.billingType}, :#{#e.receivedAt})
            ON CONFLICT DO NOTHING
            """, nativeQuery = true)
    int insertIgnoringDuplicate(@Param("e") AsaasEvent e);

    boolean existsByEventId(String eventId);

    List<AsaasEvent> findByReceivedAtGreaterThanEqualAndReceivedAtLessThanOrderByReceivedAtAsc(
            OffsetDateTime from, OffsetDateTime to);
}
