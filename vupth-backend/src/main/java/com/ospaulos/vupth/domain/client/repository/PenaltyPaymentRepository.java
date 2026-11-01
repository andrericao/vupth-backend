package com.ospaulos.vupth.domain.client.repository;

import com.ospaulos.vupth.domain.client.model.PenaltyPayment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface PenaltyPaymentRepository extends JpaRepository<PenaltyPayment, UUID> {

    List<PenaltyPayment> findByStatusOrderByCreatedAtAsc(PenaltyPayment.Status status);

    List<PenaltyPayment> findByClientIdAndStatus(UUID clientId, PenaltyPayment.Status status);
}
