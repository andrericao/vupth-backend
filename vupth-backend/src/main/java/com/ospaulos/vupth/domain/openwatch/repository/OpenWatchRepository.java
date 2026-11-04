package com.ospaulos.vupth.domain.openwatch.repository;

import com.ospaulos.vupth.domain.openwatch.model.OpenWatch;
import com.ospaulos.vupth.domain.openwatch.model.OpenWatchKind;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface OpenWatchRepository extends JpaRepository<OpenWatch, UUID> {

    List<OpenWatch> findByUserIdAndDayOrderByCreatedAtAsc(UUID userId, LocalDate day);

    long countByUserIdAndDay(UUID userId, LocalDate day);

    /** Os pedidos ainda nao disparados de uma loja, de um tipo, no dia. */
    List<OpenWatch> findByEstablishmentIdAndKindAndDayAndFiredAtIsNull(UUID establishmentId, OpenWatchKind kind, LocalDate day);
}
