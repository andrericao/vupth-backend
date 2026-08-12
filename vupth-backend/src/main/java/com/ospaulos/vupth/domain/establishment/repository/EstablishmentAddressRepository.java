package com.ospaulos.vupth.domain.establishment.repository;

import com.ospaulos.vupth.domain.establishment.model.EstablishmentAddress;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface EstablishmentAddressRepository extends JpaRepository<EstablishmentAddress, UUID> {
    List<EstablishmentAddress> findAllByEstablishmentId(UUID establishmentId);

    /**
     * Vinculo pelo id do {@code Address} (backlog #3 — o PATCH). O id que a API expoe e o do endereco,
     * nao o do vinculo: e ele que o geocoding usa e o unico que aparece no
     * {@code EstablishmentAddressResponse}. Casar com o {@code establishmentId} no proprio filtro
     * impede que o dono de uma loja edite o endereco de outra passando um id valido de terceiro.
     */
    Optional<EstablishmentAddress> findByEstablishmentIdAndAddressId(UUID establishmentId, UUID addressId);
}
