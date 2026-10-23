package com.ospaulos.vupth.infrastructure.asaas;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public interface AsaasEventRepository extends JpaRepository<AsaasEvent, UUID> {

    boolean existsByEventId(String eventId);

    List<AsaasEvent> findByReceivedAtGreaterThanEqualAndReceivedAtLessThanOrderByReceivedAtAsc(
            OffsetDateTime from, OffsetDateTime to);
}
