package com.ospaulos.vupth.domain.attendance.fitin.repository;

import com.ospaulos.vupth.domain.attendance.fitin.model.FitInRequest;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface FitInRequestRepository extends JpaRepository<FitInRequest, UUID> {

    List<FitInRequest> findByProfessionalIdOrderByCreatedAtDesc(UUID professionalId);

    List<FitInRequest> findByClientIdOrderByCreatedAtDesc(UUID clientId);

    boolean existsByClientIdAndProfessionalIdAndStatusIn(UUID clientId, UUID professionalId,
                                                         java.util.Collection<com.ospaulos.vupth.domain.attendance.fitin.model.FitInStatus> statuses);
}
