package com.ospaulos.vupth.domain.address.repository;

import com.ospaulos.vupth.domain.address.model.Address;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface AddressRepository extends JpaRepository<Address, UUID> {

    /**
     * Enderecos ainda sem coordenada — alvo do backfill (backlog #2). Enquanto estiverem assim, o
     * estabelecimento nao aparece na busca por raio.
     */
    List<Address> findByLatitudeIsNullOrLongitudeIsNull();
}
