package com.ospaulos.vupth.domain.establishment.transfer;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Pedidos de passagem de posse (desativar-conta). No maximo um PENDING por loja. */
public interface OwnershipTransferRepository extends JpaRepository<OwnershipTransfer, UUID> {

    Optional<OwnershipTransfer> findFirstByEstablishmentIdAndStatus(UUID establishmentId, OwnershipTransfer.Status status);

    List<OwnershipTransfer> findByToProfessionalIdAndStatusOrderByCreatedAtDesc(UUID toProfessionalId, OwnershipTransfer.Status status);
}
