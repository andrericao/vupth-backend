package com.ospaulos.vupth.domain.referral.repository;

import com.ospaulos.vupth.domain.referral.model.ProfessionalPayment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ProfessionalPaymentRepository extends JpaRepository<ProfessionalPayment, UUID> {

    boolean existsByReversalOf(UUID paymentId);

    List<ProfessionalPayment> findByProfessionalIdOrderByPaidAtDesc(UUID professionalId);
}
